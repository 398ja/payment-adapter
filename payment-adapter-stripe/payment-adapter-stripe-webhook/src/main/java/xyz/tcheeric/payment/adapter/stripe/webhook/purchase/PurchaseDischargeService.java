package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * Closing a debt, on evidence rather than on a claim.
 *
 * <p>The issuer says "I issued this". This service asks the gateway whether a
 * completed send answered the payment request, and only then marks it done.
 * That is {@code imani-woo} ADR 0009's rule — fulfilment is confirmed, not
 * asserted — applied to a machine rather than to a distracted merchant. The
 * failure is identical either way: a debt marked done that nobody discharged
 * leaves a customer who paid for nothing, and nothing can tell.
 *
 * <h2>Three outcomes, never two</h2>
 *
 * <p>The temptation is to treat "could not verify" as "not verified". That
 * collapses a gateway outage into an accusation, and the two need opposite
 * responses. So an unreachable gateway leaves the debt exactly as it was and
 * asks the caller to come back.
 */
@Slf4j
@RequiredArgsConstructor
public class PurchaseDischargeService {

    private final StripePurchaseRepository purchases;
    private final GatewayFulfilmentClient fulfilment;

    /** What happened to a discharge request, in terms the caller can act on. */
    public enum Result {
        /** Confirmed and closed. */
        DISCHARGED,
        /** The gateway says no completed send answered this. The debt stands. */
        REFUSED,
        /** The gateway could not be asked. Try again; nothing has changed. */
        UNVERIFIABLE,
        /** Already closed by an earlier call. */
        ALREADY_DISCHARGED,
        /** No such purchase. */
        UNKNOWN_PURCHASE
    }

    /**
     * Try to close one debt.
     *
     * @param eventId   which purchase, by the id the issuer was given
     * @param voucherId the coupon the issuer says it minted, kept as evidence
     * @param issuerId  who the issuer says minted it, checked against the gateway
     */
    @Transactional
    public Result discharge(String eventId, String voucherId, String issuerId) {
        Optional<StripePurchase> found = purchases.findByEventId(eventId);
        if (found.isEmpty()) {
            return Result.UNKNOWN_PURCHASE;
        }

        StripePurchase purchase = found.get();

        // Idempotent by design. The issuer may retry a discharge whose response
        // it never saw, and a second confirmation must not look like a second
        // sale.
        if (purchase.getStatus() == StripePurchase.Status.DISCHARGED) {
            return Result.ALREADY_DISCHARGED;
        }

        // An ISSUED purchase can still be discharged: the coupon exists and
        // this is the call that confirms it reached the buyer. Only minting
        // again is forbidden, and that decision lives in the OWED queue.

        GatewayFulfilmentClient.Answer answer = fulfilment.check(purchase.getPaymentRequestId());

        if (answer.fulfilment() == Fulfilment.UNKNOWN) {
            // Deliberately NOT recorded as a failed attempt. Nothing was
            // learned about the issuer's work, so counting it would make an
            // outage look like a struggling issuer.
            log.info("Could not verify purchase {}; leaving it owed", eventId);
            return Result.UNVERIFIABLE;
        }

        if (answer.fulfilment() == Fulfilment.NOT_FULFILLED) {
            // The issuer claimed something the gateway does not see. Recorded
            // against the row, because a claim that cannot be substantiated is
            // exactly what somebody needs to look at.
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure("issuer reported a discharge the gateway cannot confirm");
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
            log.warn("Refused discharge of {}: no completed send answers its request", eventId);
            return Result.REFUSED;
        }

        // Fulfilled by SOMEBODY. ADR 0009 also checks who: a completed send
        // from another stall does not discharge this stall's debt.
        String confirmedIssuer = answer.issuerId();
        if (issuerId != null && confirmedIssuer != null && !issuerId.equalsIgnoreCase(confirmedIssuer)) {
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure("a send answered this request, but from issuer " + confirmedIssuer);
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
            log.warn("Refused discharge of {}: answered by {} rather than {}",
                    eventId, confirmedIssuer, issuerId);
            return Result.REFUSED;
        }

        purchase.setStatus(StripePurchase.Status.DISCHARGED);
        // The coupon, not the issuer. These are different things and storing
        // one under the other's name is how a trail stops leading anywhere:
        // the voucher id is what someone follows to the coupon itself.
        purchase.setVoucherId(voucherId);
        purchase.setLastFailure(null);
        purchase.setUpdatedAt(Instant.now());
        purchases.save(purchase);

        log.info("Discharged purchase {} on confirmed issuance", eventId);
        return Result.DISCHARGED;
    }

    /**
     * Record that an attempt failed, without closing anything.
     *
     * <p>Separate from {@link #discharge} because a failure is not a discharge
     * with a different verdict: nothing is verified, nothing is closed, and the
     * only change is that somebody can see this debt is struggling.
     */
    @Transactional
    public void recordFailure(String eventId, String reason, boolean permanent, String voucherId) {
        purchases.findByEventId(eventId).ifPresent(purchase -> {
            if (purchase.getStatus() == StripePurchase.Status.DISCHARGED) {
                return;
            }
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));

            if (voucherId != null && !voucherId.isBlank()) {
                /*
                 * A coupon EXISTS. This must leave the mintable queue.
                 *
                 * Left OWED it would be read as unminted on the next pass and
                 * minted again — one payment producing a coupon every time the
                 * worker wakes. The voucher id is kept because it is the only
                 * handle on value that was already created.
                 */
                purchase.setVoucherId(voucherId);
                purchase.setStatus(StripePurchase.Status.ISSUED);
            } else {
                // BLOCKED is not "given up on". It is "retrying cannot help",
                // which is a different thing and needs a person, not a timer.
                purchase.setStatus(permanent
                        ? StripePurchase.Status.BLOCKED
                        : StripePurchase.Status.OWED);
            }

            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
        });
    }
}
