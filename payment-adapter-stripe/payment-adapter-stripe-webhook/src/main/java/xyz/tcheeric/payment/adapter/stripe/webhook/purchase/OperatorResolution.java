package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveAction;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveResult;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.RetryRefusal.Reason;

/**
 * An operator's {@code /resolve}, with a live Stripe read in front of every {@code retry} of a parked
 * purchase (#286 item 3).
 *
 * <p>A retry is the one operator action that can lead to a mint (OWED) or a delivery (ISSUED). The row
 * alone is not evidence the money is still there: with the refund or dispute webhook missed, the row
 * says "intact" and the retry mints for a refunded payment, which is what happened on staging. So the
 * PaymentIntent, its latest charge, refunds and dispute are read live first, and any refund or dispute
 * the row lacked is recorded through the webhook's own listener path.
 *
 * <ul>
 *   <li>Disputed: refused. A dispute can still be lost (#265).</li>
 *   <li>Refunded, coupon exists: refused. Delivering it gives away a coupon nobody paid for.</li>
 *   <li>Refunded, no coupon, and the row did not know: refused, with the refund now recorded. A second
 *       retry closes the row as REFUNDED, which never mints (#282).</li>
 *   <li>Stripe unreachable: fail closed. Nothing moves, the caller is told to try again.</li>
 * </ul>
 *
 * <p>Not transactional on purpose: the Stripe read must not hold a database connection, and the facts
 * it records commit on their own before the transactional {@link PurchaseDischargeService#resolve}
 * re-reads the row.
 */
@Slf4j
@RequiredArgsConstructor
public class OperatorResolution {

    private final StripePurchaseRepository purchases;
    private final PurchaseDischargeService discharges;
    private final LivePaymentCheck livePayments;

    /** What came of a resolve. */
    public sealed interface Outcome {
    }

    /** The resolve ran; its result is the service's. */
    public record Applied(ResolveResult result) implements Outcome {
    }

    /** The live check refused the retry. Nothing moved, though new Stripe facts may be recorded. */
    public record Refused(RetryRefusal refusal) implements Outcome {
    }

    /** Stripe could not be read, so the retry was not attempted. Nothing changed. */
    public record Unverifiable(String detail) implements Outcome {
    }

    public Outcome resolve(String eventId, ResolveAction action, String voucherId, String operator, String note) {
        Optional<StripePurchase> parked = purchases.findByEventId(eventId).filter(OperatorResolution::isParked);
        if (action == ResolveAction.RETRY && parked.isPresent()) {
            Optional<Outcome> stop = liveRefusal(parked.get(), operator);
            if (stop.isPresent()) {
                return stop.get();
            }
        }
        return new Applied(discharges.resolve(eventId, action, voucherId, operator, note));
    }

    private static boolean isParked(StripePurchase purchase) {
        return purchase.getStatus() == StripePurchase.Status.NEEDS_ATTENTION;
    }

    private Optional<Outcome> liveRefusal(StripePurchase purchase, String operator) {
        if (purchase.getPaymentIntentId() == null || purchase.getPaymentIntentId().isBlank()) {
            return Optional.of(refused(purchase, operator, Reason.NO_PAYMENT_INTENT,
                    "the purchase names no PaymentIntent, so Stripe cannot be checked; resolve it by hand"));
        }
        LivePayment live;
        try {
            live = livePayments.refresh(purchase);
        } catch (LivePaymentUnavailableException e) {
            log.warn("[alert] Retry of purchase {} by {} not attempted: Stripe could not be read ({})",
                    purchase.getEventId(), operator, e.getMessage());
            return Optional.of(new Unverifiable("Stripe could not be read to confirm the payment is intact;"
                    + " nothing changed, try again later"));
        }
        return refusalFor(purchase, live).map(reason -> refused(purchase, operator, reason, detail(reason, live)));
    }

    private static Optional<Reason> refusalFor(StripePurchase purchase, LivePayment live) {
        if (live.disputed()) {
            return Optional.of(Reason.DISPUTED);
        }
        if (live.refunded() && purchase.getVoucherId() != null) {
            return Optional.of(Reason.REFUNDED_AFTER_ISSUE);
        }
        if (live.refunded() && live.newFacts()) {
            return Optional.of(Reason.REFUNDED);
        }
        return Optional.empty();
    }

    private static String detail(Reason reason, LivePayment live) {
        String facts = "Stripe shows refunded " + live.refundedMinor() + " of " + live.amountMinor()
                + ", disputed " + live.disputed() + (live.newFacts() ? " (now recorded)" : "");
        return facts + "; " + switch (reason) {
            case DISPUTED -> "a disputed payment is never minted or delivered; leave it parked, or"
                    + " resolve as discharged once a coupon was delivered by hand";
            case REFUNDED_AFTER_ISSUE -> "the money went back after the coupon was minted; acknowledge it,"
                    + " the coupon is not delivered";
            case REFUNDED -> "the row did not know about the refund; retry again to close it as REFUNDED,"
                    + " which never mints";
            case NO_PAYMENT_INTENT -> "";
        };
    }

    private static Outcome refused(StripePurchase purchase, String operator, Reason reason, String detail) {
        log.warn("Retry of purchase {} by {} refused ({}): {}", purchase.getEventId(), operator, reason.wire(), detail);
        return new Refused(RetryRefusal.of(reason, detail));
    }
}
