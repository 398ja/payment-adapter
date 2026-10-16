package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * Rows nothing automatic will move on, sent to a person (#272 step 5). No blind re-mint (gc#232):
 * a stale row is never put back to OWED here, only parked, or made REFUNDED when Stripe says the
 * money went back before any coupon existed.
 *
 * <ul>
 *   <li>ISSUING whose lease lapsed more than {@code issuingStaleAfter} ago: {@code stale_issuing}.</li>
 *   <li>ISSUED older than {@code issuedStaleAfter}: {@code stale_issued} (the coupon exists).</li>
 *   <li>BLOCKED (a permanent mint refusal, staging purchase #1): {@code issue_blocked} when the
 *       payment is intact, so it is on the operator's list instead of nowhere; REFUNDED when it was
 *       refunded; {@code disputed_before_issue} when disputed.</li>
 * </ul>
 *
 * <p>The live PaymentIntent is read before every move, so the facts on the row are Stripe's.
 */
@Slf4j
@RequiredArgsConstructor
class StaleRows {

    private final StripeReconcileSource stripe;
    private final StripePurchaseRepository purchases;
    private final StripePurchaseListener listener;
    private final TransactionOperations transactions;
    private final Duration issuingStaleAfter;
    private final Duration issuedStaleAfter;
    private final int batchSize;

    /** A fresh budget for one sweep: at most one PaymentIntent read per row looked at. */
    ReconcileBudget newBudget() {
        return new ReconcileBudget(batchSize);
    }

    /** @return rows moved */
    int sweep(Instant now, ReconcileBudget budget) {
        Instant issuingCutoff = now.minus(issuingStaleAfter);
        Instant issuedCutoff = now.minus(issuedStaleAfter);
        List<StripePurchase> stale = purchases.findStaleForAttention(Status.ISSUING, issuingCutoff,
                Status.ISSUED, issuedCutoff, Status.BLOCKED, PageRequest.of(0, batchSize));
        int moved = 0;
        for (StripePurchase row : stale) {
            if (!budget.spend()) {
                break;
            }
            try {
                PaymentState live;
                String unread = null;
                try {
                    live = row.getPaymentIntentId() == null
                            ? null : stripe.paymentState(row.getConnectedAccountId(), row.getPaymentIntentId());
                } catch (StripeReconcileException e) {
                    if (e.isRateLimited()) {
                        budget.rateLimited();
                        log.warn("stripe_reconcile stale row {} left for the next run: {}", row.getEventId(),
                                e.getMessage());
                        continue;
                    }
                    // Fail closed (review of #282): parking never makes a row mintable, so a row
                    // Stripe will not answer about (deauthorized account, deleted intent) is parked
                    // with that said, instead of staying invisible and retried forever.
                    live = null;
                    unread = e.getMessage();
                }
                PaymentState facts = live;
                String readError = unread;
                Boolean done = transactions.execute(
                        status -> move(row.getEventId(), facts, readError, now, issuingCutoff, issuedCutoff));
                if (Boolean.TRUE.equals(done)) {
                    moved++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("stripe_reconcile stale row {} changed meanwhile; next run", row.getEventId());
            }
        }
        return moved;
    }

    /**
     * Re-reads the row and re-applies the staleness test to it (review of #282): an issuer may have
     * re-claimed a lapsed ISSUING row while Stripe was being read, and is minting under a live lease.
     */
    private boolean move(String eventId, PaymentState live, String readError, Instant now,
            Instant issuingCutoff, Instant issuedCutoff) {
        StripePurchase before = purchases.findByEventId(eventId).orElse(null);
        if (before == null || !stillStale(before, issuingCutoff, issuedCutoff)) {
            return false;
        }
        if (live != null && !live.intact()) {
            LiveFacts.record(listener, eventId, before.getConnectedAccountId(), before.getPaymentIntentId(), live);
        }
        StripePurchase row = purchases.findByEventId(eventId).orElse(null);
        if (row == null) {
            return false;
        }
        AttentionReason reason = reasonFor(row, live);
        if (reason == null) {
            return row.getStatus() == Status.REFUNDED;
        }
        park(row, reason, detailFor(row, live, readError), now);
        purchases.save(row);
        log.warn("[alert] stripe_reconcile parked purchase {} ({}): {}", eventId, reason.wire(),
                row.getAttentionDetail());
        return true;
    }

    private static boolean stillStale(StripePurchase row, Instant issuingCutoff, Instant issuedCutoff) {
        return switch (row.getStatus()) {
            case ISSUING -> row.getClaimedUntil() != null && row.getClaimedUntil().isBefore(issuingCutoff);
            case ISSUED -> row.getCreatedAt() != null && row.getCreatedAt().isBefore(issuedCutoff);
            case BLOCKED -> true;
            default -> false;
        };
    }

    /** Null when the row needs no parking: it became REFUNDED, or moved on meanwhile. */
    private static AttentionReason reasonFor(StripePurchase row, PaymentState live) {
        return switch (row.getStatus()) {
            case ISSUING -> AttentionReason.STALE_ISSUING;
            case ISSUED -> AttentionReason.STALE_ISSUED;
            case BLOCKED -> live != null && live.disputed()
                    ? AttentionReason.DISPUTED_BEFORE_ISSUE : AttentionReason.ISSUE_BLOCKED;
            default -> null;
        };
    }

    private static String detailFor(StripePurchase row, PaymentState live, String readError) {
        String stripeFacts = readError != null ? "live check FAILED (" + readError + "): check Stripe by hand"
                : live == null ? "no payment intent to check"
                : "live: refunded " + live.refundedMinor() + " of " + live.amountMinor() + ", disputed " + live.disputed();
        String last = row.getLastFailure() == null ? "" : "; last failure: " + row.getLastFailure();
        return "reconciler: " + row.getStatus().name().toLowerCase(java.util.Locale.ROOT) + " too long; "
                + stripeFacts + last;
    }

    /** Same fields {@code PurchaseDischargeService} sets when it parks (#273). */
    static void park(StripePurchase row, AttentionReason reason, String detail, Instant now) {
        row.setStatus(Status.NEEDS_ATTENTION);
        row.setAttentionReason(reason);
        row.setAttentionDetail(detail == null ? null : detail.substring(0, Math.min(500, detail.length())));
        row.setAttentionAt(now);
        row.setClaimedUntil(null);
        row.setUpdatedAt(now);
    }
}
