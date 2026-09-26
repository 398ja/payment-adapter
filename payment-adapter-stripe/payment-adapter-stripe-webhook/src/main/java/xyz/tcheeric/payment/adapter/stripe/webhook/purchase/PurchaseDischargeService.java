package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
public class PurchaseDischargeService {

    private final StripePurchaseRepository purchases;
    private final GatewayFulfilmentClient fulfilment;
    /**
     * How long a claim holds before another worker may take the row over
     * (imani-wallet#96). Long against a mint (seconds), because a lapsed
     * claim is re-minted under the same idempotency key, and until the
     * gateway's key store is durable that re-mint is only as safe as the
     * gateway not having restarted in between.
     */
    private final Duration claimLease;

    public PurchaseDischargeService(StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment) {
        this(purchases, fulfilment, Duration.ofMinutes(10));
    }

    public PurchaseDischargeService(
            StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment, Duration claimLease) {
        this.purchases = purchases;
        this.fulfilment = fulfilment;
        this.claimLease = claimLease;
    }

    /** The answer to a claim. */
    public enum ClaimResult {
        /** This caller holds the row and may mint for it. */
        CLAIMED,
        /** Somebody else holds it, or it is no longer owed. Do not mint. */
        NOT_CLAIMABLE,
        /** No such purchase. */
        UNKNOWN_PURCHASE
    }

    /**
     * Claim one purchase before minting for it (imani-wallet#96).
     *
     * <p>The database decides: {@link StripePurchaseRepository#claim} is one
     * conditional update, so of any number of workers racing one row exactly
     * one gets CLAIMED. Mint only on CLAIMED.
     */
    @Transactional
    public ClaimResult claim(String eventId, String worker) {
        if (purchases.findByEventId(eventId).isEmpty()) {
            return ClaimResult.UNKNOWN_PURCHASE;
        }
        Instant now = Instant.now();
        String who = worker == null || worker.isBlank()
                ? "unnamed"
                : worker.substring(0, Math.min(128, worker.length()));
        int updated = purchases.claim(eventId, who, now, now.plus(claimLease),
                StripePurchase.Status.OWED, StripePurchase.Status.ISSUING);
        return updated == 1 ? ClaimResult.CLAIMED : ClaimResult.NOT_CLAIMABLE;
    }

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
        purchase.setClaimedUntil(null);
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
        recordFailure(eventId, reason, permanent, voucherId, null, false);
    }

    /**
     * @param worker    who is reporting; when set, a report cannot release a
     *                  claim another worker still holds (imani-wallet#96)
     * @param keepClaim the outcome is ambiguous (the mint may still be in
     *                  flight), so record the note but leave the claim to
     *                  lapse rather than handing the row straight back
     */
    @Transactional
    public void recordFailure(String eventId, String reason, boolean permanent, String voucherId,
            String worker, boolean keepClaim) {
        purchases.findByEventId(eventId).ifPresent(purchase -> {
            if (purchase.getStatus() == StripePurchase.Status.DISCHARGED) {
                return;
            }
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));

            Instant now = Instant.now();
            boolean heldByAnother = purchase.getStatus() == StripePurchase.Status.ISSUING
                    && worker != null && purchase.getClaimedBy() != null
                    && !worker.equals(purchase.getClaimedBy())
                    && purchase.getClaimedUntil() != null && purchase.getClaimedUntil().isAfter(now);

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
                purchase.setClaimedUntil(null);
            } else if (purchase.getStatus() == StripePurchase.Status.ISSUED) {
                // A coupon already exists for this row (an earlier report
                // named it). A later report that names none must NOT send it
                // back to OWED: that is the re-mint this state exists to stop
                // (imani-wallet#96).
                log.warn("Failure report for already-issued purchase {} ignored for status", eventId);
            } else if (heldByAnother) {
                // A worker whose claim lapsed reporting late, while another
                // worker now holds the row and may be minting. Releasing it
                // would let a third worker mint concurrently (imani-wallet#96).
                log.warn("Late failure report for purchase {} from {} ignored; {} holds the claim",
                        eventId, worker, purchase.getClaimedBy());
            } else if (keepClaim && purchase.getStatus() == StripePurchase.Status.ISSUING) {
                // Ambiguous outcome: the mint may have happened. The claim is
                // left to lapse, so the retry comes after any in-flight
                // request has settled and the idempotency key can answer it.
            } else {
                // BLOCKED is not "given up on". It is "retrying cannot help",
                // which is a different thing and needs a person, not a timer.
                // From ISSUING this releases the claim: nothing was minted.
                purchase.setStatus(permanent
                        ? StripePurchase.Status.BLOCKED
                        : StripePurchase.Status.OWED);
                purchase.setClaimedUntil(null);
            }

            purchase.setUpdatedAt(now);
            purchases.save(purchase);
        });
    }
}
