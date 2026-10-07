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

        if (answer.fulfilment() == Fulfilment.NOT_FULFILLED && answer.unregistered()) {
            // Recorded before registration existed, or while the gateway was
            // unreachable. Register now and ask again.
            GatewayFulfilmentClient.Registration late = registerLate(purchase);
            if (late == GatewayFulfilmentClient.Registration.REGISTERED
                    || late == GatewayFulfilmentClient.Registration.ALREADY_REGISTERED) {
                answer = fulfilment.check(purchase.getPaymentRequestId());
            } else if (late == GatewayFulfilmentClient.Registration.FAILED
                    || late == GatewayFulfilmentClient.Registration.UNSUPPORTED) {
                // The PUT timed out, got a 5xx, or this core has no PUT yet.
                // That says nothing about the issuer's work: same as an
                // outage, not a refusal (payment-adapter#260 review, PA-3).
                log.info("Could not register purchase {} late ({}); leaving it for a retry", eventId, late);
                return Result.UNVERIFIABLE;
            }
            // null (no buyer key or stall: cannot ever be registered) and
            // CONFLICT (somebody else's terms won) fall through to REFUSED:
            // retrying cannot help, and a person needs to look.
        }

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

        // The facts behind the verdict must be the ones this purchase is owed
        // (imani-gateway-core#131). A gateway that says "fulfilled" without
        // them is one from before #131, whose answer a 1-sat send to oneself
        // could produce: not an accusation, so UNVERIFIABLE, but no discharge.
        // The terms must also be OURS. Registration is first-writer-wins and
        // open to any NIP-98 key, so a squatter can register matching terms
        // first and our PUT is answered 200. core#131 (fb2b76a) names the
        // registering key; a core that does not cannot say whose terms these
        // are, which is unverifiable like the missing facts above.
        String mismatch = mismatch(purchase, answer, fulfilment.publicKeyHex());
        if (mismatch != null) {
            if (answer.recipientPubkey() == null || answer.amount() == null || answer.unit() == null
                    || answer.creatorPubkey() == null) {
                log.info("Gateway confirmed {} without the facts to check; leaving it owed", eventId);
                return Result.UNVERIFIABLE;
            }
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure("gateway answer does not match the purchase: " + mismatch);
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
            log.warn("Refused discharge of {}: {}", eventId, mismatch);
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
     * Why this answer cannot close this purchase, or null when it can.
     *
     * <p>Recipient, amount, unit and stall must all be the purchase's own. Any
     * of them missing is a reason too: the check is only as strong as its
     * weakest fact. So must the registering key: only terms this service
     * registered itself ({@code ownKey}) count.
     */
    static String mismatch(StripePurchase purchase, GatewayFulfilmentClient.Answer answer, String ownKey) {
        if (answer.recipientPubkey() == null || answer.amount() == null || answer.unit() == null) {
            return "the answer carries no recipient, amount or unit";
        }
        if (answer.creatorPubkey() == null) {
            return "the answer does not say who registered the request";
        }
        if (ownKey == null || !ownKey.equalsIgnoreCase(answer.creatorPubkey())) {
            return "registered by " + answer.creatorPubkey() + ", not this service";
        }
        if (purchase.getRecipientPubkey() == null
                || !purchase.getRecipientPubkey().equalsIgnoreCase(answer.recipientPubkey())) {
            return "paid to " + answer.recipientPubkey() + ", not the buyer";
        }
        if (purchase.getAmountMinor() == null || answer.amount() < purchase.getAmountMinor()) {
            return "paid " + answer.amount() + " of " + purchase.getAmountMinor();
        }
        if (purchase.getCurrency() == null || !unitOf(purchase.getCurrency()).equalsIgnoreCase(answer.unit())) {
            return "paid in " + answer.unit() + ", not " + purchase.getCurrency();
        }
        if (purchase.getStallPubkey() != null
                && (answer.issuerId() == null || !purchase.getStallPubkey().equalsIgnoreCase(answer.issuerId()))) {
            return "issued by " + answer.issuerId() + ", not the stall";
        }
        return null;
    }

    /** The unit a currency is registered under: Stripe's lower-case ISO code, upper-cased. */
    static String unitOf(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Register a purchase the listener could not, and say what happened.
     *
     * <p>Safe after the fact, though not because the id is secret: by
     * discharge time the issuer has already sent the buyer a DM carrying it
     * (imani-wallet {@code delivery.ts}), so the buyer, or anyone who reads
     * that DM, can register first. What makes it safe is that core binds the
     * creator into the terms, so a squatter's registration answers our PUT
     * with 409, and {@link #mismatch} compares any later answer against this
     * row's own recipient, amount, unit and stall, never against echoed
     * terms. The worst a squatter achieves is a debt left for a person to
     * check, and only against their own purchase.
     *
     * @return the gateway's answer, or null when the row lacks the facts to register
     */
    private GatewayFulfilmentClient.Registration registerLate(StripePurchase purchase) {
        if (purchase.getRecipientPubkey() == null || purchase.getStallPubkey() == null
                || purchase.getAmountMinor() == null || purchase.getCurrency() == null) {
            return null;
        }
        return fulfilment.register(
                purchase.getPaymentRequestId(), purchase.getRecipientPubkey(), purchase.getStallPubkey(),
                purchase.getAmountMinor(), unitOf(purchase.getCurrency()));
    }

    /** How long a re-checked ISSUED row rests before it is asked about again. */
    static final Duration RECHECK_BACKOFF = Duration.ofMinutes(15);
    /** After this, an unverifiable ISSUED row needs a person, not another request. */
    static final Duration RECHECK_HORIZON = Duration.ofDays(14);
    /** Rows per sweep, so one sweep cannot flood the gateway. */
    static final int RECHECK_BATCH = 50;

    /**
     * Ask again about coupons that exist but whose discharge was never
     * confirmed (payment-adapter#260 review, PA-2).
     *
     * <p>The issuer calls {@code /discharge} once, right after minting. A 503
     * (old core, outage, failed late registration) makes it report a failure
     * naming the voucher, which parks the row in ISSUED: correctly, because
     * OWED would mint again. Before this sweep nothing ever asked about an
     * ISSUED row again, so "unverifiable, try later" meant "never". Now every
     * ISSUED row is re-discharged with back-off until it closes, is refused,
     * or ages past {@link #RECHECK_HORIZON}. That is what makes the rollout
     * safe in either deploy order: rows recorded against an old core close by
     * themselves once core#131 is live.
     *
     * <p>No status ever moves backwards here. A row that still cannot be
     * verified stays ISSUED with its clock moved on, and no attempt is counted.
     *
     * @return how many rows were discharged
     */
    public int recheckIssued(Instant now) {
        int discharged = 0;
        for (StripePurchase row : purchases.findIssuedDueForRecheck(StripePurchase.Status.ISSUED,
                now.minus(RECHECK_BACKOFF), now.minus(RECHECK_HORIZON),
                org.springframework.data.domain.PageRequest.of(0, RECHECK_BATCH))) {
            Result result;
            try {
                // The issuer is not on the line, so no issuer id to compare:
                // the row's own stall is still checked by mismatch().
                result = discharge(row.getEventId(), row.getVoucherId(), null);
            } catch (RuntimeException e) {
                log.warn("Re-check of issued purchase {} failed: {}", row.getEventId(), e.getMessage());
                result = Result.UNVERIFIABLE;
            }
            if (result == Result.DISCHARGED) {
                discharged++;
            } else if (row.getStatus() == StripePurchase.Status.ISSUED) {
                row.setUpdatedAt(Instant.now());
                purchases.save(row);
            }
        }
        if (discharged > 0) {
            log.info("Re-check discharged {} issued purchase(s)", discharged);
        }
        return discharged;
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
