package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.CompletedSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;

/**
 * Records a paid Checkout Session that has no {@code stripe_purchase} row (#272, audit S10).
 *
 * <p>Through the same {@link StripePurchaseListener} the webhook uses, with the event id
 * {@code reconcile:<cs_id>}. The listener looks the session up first and the V14 uniques refuse a
 * second row, so a webhook racing this is harmless either way round.
 *
 * <p><b>The live payment is read first</b> (owner's rule, PA#265/#277). Refunded: the row is
 * recorded and made REFUNDED in the SAME transaction, so no issuer ever sees it OWED. Disputed:
 * recorded and parked {@code disputed_before_issue}, also in one transaction. Only an intact
 * payment becomes OWED.
 */
@Slf4j
@RequiredArgsConstructor
class LostPurchaseRecorder {

    static final String EVENT_PREFIX = "reconcile:";

    enum Outcome { NOT_PAID, ALREADY_RECORDED, RECORDED_OWED, RECORDED_REFUNDED, RECORDED_PARKED, DEFERRED, FOREIGN }

    private final StripeReconcileSource stripe;
    private final StripePurchaseRepository purchases;
    private final StripePurchaseListener listener;
    private final TransactionOperations transactions;
    private final PaymentMetrics metrics;

    /**
     * Whether a session was created by another deployment, so it is not ours to record. Asked before
     * the live read, so a foreign session costs no Stripe call. Never true without a configured
     * environment (see RecordingPurchaseListener#withEnvironment).
     */
    private java.util.function.Predicate<CompletedSession> foreign = session -> false;

    LostPurchaseRecorder withForeign(java.util.function.Predicate<CompletedSession> foreign) {
        this.foreign = foreign == null ? session -> false : foreign;
        return this;
    }

    Outcome reconcile(String accountId, CompletedSession session, ReconcileBudget budget) {
        if (!session.paid()) {
            return Outcome.NOT_PAID;
        }
        if (foreign.test(session)) {
            return Outcome.FOREIGN;
        }
        // A subscription-mode session is a plan. Its instalments are recorded from their invoices, so recording the
        // session as a purchase would issue the first instalment twice.
        if (xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanService.isPlanEvent(session.metadata())) {
            return Outcome.FOREIGN;
        }
        if (purchases.findByCheckoutSessionId(session.id()).isPresent()) {
            return Outcome.ALREADY_RECORDED;
        }
        return recordLost(accountId, new Paid("purchase", session.id(), session.paymentIntentId(),
                new PaidPurchase(EVENT_PREFIX + session.id(), session.id(), session.paymentIntentId(), accountId,
                        session.amountTotal(), session.currency(), session.metadata(), session.livemode())), budget);
    }

    /**
     * Records a paid instalment that has no {@code stripe_purchase} row, with the SAME live-payment-first rules as a
     * one-off purchase. The caller (the instalment reconciler) has already built the purchase through the plan
     * service, so the buyer is the plan's and the invoice id is the ledger key.
     */
    Outcome reconcileInstalment(String accountId, PaidPurchase purchase, ReconcileBudget budget) {
        if (purchases.findByCheckoutSessionId(purchase.checkoutSessionId()).isPresent()) {
            return Outcome.ALREADY_RECORDED;
        }
        return recordLost(accountId, new Paid("instalment", purchase.checkoutSessionId(), purchase.paymentIntentId(),
                purchase), budget);
    }

    /** What is being recovered: its kind (for the alert), its ledger key, its payment intent, and what to record. */
    private record Paid(String kind, String key, String paymentIntentId, PaidPurchase purchase) {}

    private Outcome recordLost(String accountId, Paid paid, ReconcileBudget budget) {
        if (!budget.spend()) {
            return Outcome.DEFERRED;
        }
        PaymentState live = stripe.paymentState(accountId, paid.paymentIntentId());
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> record(accountId, paid, live));
        } catch (DataIntegrityViolationException raced) {
            // Only a row for this payment proves a racing webhook won. Anything else (a column too
            // long, a NOT NULL) recorded nothing, and calling it "already recorded" would move the
            // cursor past a paid, unrecorded payment for good (review of #282).
            if (purchases.findByCheckoutSessionId(paid.key()).isPresent()
                    || purchases.findByPaymentIntentId(paid.paymentIntentId()).isPresent()) {
                log.info("stripe_reconcile {} {} recorded meanwhile by its webhook", paid.kind(), paid.key());
                return Outcome.ALREADY_RECORDED;
            }
            throw new StripeReconcileException("could not record paid " + paid.kind() + " " + paid.key()
                    + "; cursor kept: " + raced.getMostSpecificCause().getMessage(), raced, false);
        }
        if (outcome == Outcome.ALREADY_RECORDED) {
            return outcome;
        }
        // Counted as soon as the row is committed, so nothing after this can lose the count.
        metrics.reconciled(Source.CARD);
        if (outcome == Outcome.RECORDED_OWED) {
            outcome = recheck(accountId, paid);
        }
        log.warn("[alert] stripe_reconcile recorded a paid {} no webhook recorded: key={} account={}"
                        + " pi={} amount={} {} -> {}", paid.kind(), paid.key(), accountId, paid.paymentIntentId(),
                paid.purchase().amountMinor(), paid.purchase().currency(), outcome);
        return outcome;
    }

    /**
     * The live read happened BEFORE the row existed. A refund or dispute landing in between was
     * delivered to a webhook that found no row and acknowledged it (#267), so nothing else would
     * ever apply it. Read again now that the row is committed: from here on a webhook finds the row
     * (review of #282). Not charged to the budget: it is a safety read, at most one per new row.
     */
    private Outcome recheck(String accountId, Paid paid) {
        PaymentState after;
        try {
            after = stripe.paymentState(accountId, paid.paymentIntentId());
        } catch (StripeReconcileException e) {
            // The row is OWED on the first read's evidence. A reversal from now on reaches it by
            // webhook; only one that landed in the last few seconds is unseen. Loud, not silent.
            log.warn("[alert] stripe_reconcile could not re-read {} after recording it: {}",
                    paid.paymentIntentId(), e.getMessage());
            return Outcome.RECORDED_OWED;
        }
        if (after.intact()) {
            return Outcome.RECORDED_OWED;
        }
        String eventId = paid.purchase().eventId();
        Outcome applied = transactions.execute(status -> applyReversal(eventId, accountId, paid, after));
        return applied == null ? Outcome.RECORDED_OWED : applied;
    }

    private Outcome applyReversal(String eventId, String accountId, Paid paid, PaymentState live) {
        LiveFacts.record(listener, eventId, accountId, paid.paymentIntentId(), live);
        StripePurchase row = purchases.findByEventId(eventId).orElse(null);
        if (row == null) {
            return null;
        }
        if (row.getStatus() == StripePurchase.Status.REFUNDED) {
            return Outcome.RECORDED_REFUNDED;
        }
        // OWED only: an issuer that claimed the row in these seconds is mid-mint, and its report
        // must land on the row it claimed. The dispute is on the row as a fact either way (gauge).
        if (live.disputed() && row.getVoucherId() == null && row.getStatus() == StripePurchase.Status.OWED) {
            StaleRows.park(row, AttentionReason.DISPUTED_BEFORE_ISSUE,
                    "reconciler: charge " + live.chargeId() + " disputed before any coupon", Instant.now());
            purchases.save(row);
            return Outcome.RECORDED_PARKED;
        }
        return Outcome.RECORDED_OWED;
    }

    private Outcome record(String accountId, Paid paid, PaymentState live) {
        String eventId = paid.purchase().eventId();
        call(() -> listener.onPurchasePaid(paid.purchase()));
        if (purchases.findByEventId(eventId).isEmpty()) {
            // The listener found the session or pi_ already recorded (a webhook won the race).
            return Outcome.ALREADY_RECORDED;
        }
        if (live.intact()) {
            return Outcome.RECORDED_OWED;
        }
        LiveFacts.record(listener, eventId, accountId, paid.paymentIntentId(), live);
        if (live.refunded()) {
            return Outcome.RECORDED_REFUNDED;
        }
        StripePurchase row = purchases.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("recorded purchase " + eventId + " not found"));
        StaleRows.park(row, AttentionReason.DISPUTED_BEFORE_ISSUE,
                "reconciler: charge " + live.chargeId() + " disputed before any coupon", Instant.now());
        purchases.save(row);
        return Outcome.RECORDED_PARKED;
    }

    interface ListenerCall {
        void run() throws Exception;
    }

    static void call(ListenerCall call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("purchase listener refused a reconciled purchase", e);
        }
    }
}
