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

    enum Outcome { NOT_PAID, ALREADY_RECORDED, RECORDED_OWED, RECORDED_REFUNDED, RECORDED_PARKED, DEFERRED }

    private final StripeReconcileSource stripe;
    private final StripePurchaseRepository purchases;
    private final StripePurchaseListener listener;
    private final TransactionOperations transactions;
    private final PaymentMetrics metrics;

    Outcome reconcile(String accountId, CompletedSession session, ReconcileBudget budget) {
        if (!session.paid()) {
            return Outcome.NOT_PAID;
        }
        if (purchases.findByCheckoutSessionId(session.id()).isPresent()) {
            return Outcome.ALREADY_RECORDED;
        }
        if (!budget.spend()) {
            return Outcome.DEFERRED;
        }
        PaymentState live = stripe.paymentState(accountId, session.paymentIntentId());
        try {
            Outcome outcome = transactions.execute(status -> record(accountId, session, live));
            if (outcome == Outcome.ALREADY_RECORDED) {
                return outcome;
            }
            metrics.reconciled(Source.CARD);
            log.warn("[alert] stripe_reconcile recorded a paid purchase no webhook recorded: session={} account={}"
                            + " pi={} amount={} {} -> {}", session.id(), accountId, session.paymentIntentId(),
                    session.amountTotal(), session.currency(), outcome);
            return outcome;
        } catch (DataIntegrityViolationException raced) {
            log.info("stripe_reconcile session {} recorded meanwhile by its webhook", session.id());
            return Outcome.ALREADY_RECORDED;
        }
    }

    private Outcome record(String accountId, CompletedSession session, PaymentState live) {
        String eventId = EVENT_PREFIX + session.id();
        call(() -> listener.onPurchasePaid(new PaidPurchase(eventId, session.id(), session.paymentIntentId(),
                accountId, session.amountTotal(), session.currency(), session.metadata(), session.livemode())));
        if (purchases.findByEventId(eventId).isEmpty()) {
            // The listener found the session or pi_ already recorded (a webhook won the race).
            return Outcome.ALREADY_RECORDED;
        }
        if (live.intact()) {
            return Outcome.RECORDED_OWED;
        }
        LiveFacts.record(listener, eventId, accountId, session.paymentIntentId(), live);
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
