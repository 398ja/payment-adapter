package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Stage;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
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

    /** imani-deploy#210. Set by the configuration; global registry otherwise. */
    private PaymentMetrics metrics = new PaymentMetrics();

    public PurchaseDischargeService withMetrics(PaymentMetrics metrics) {
        this.metrics = metrics.preRegister(Source.CARD, Status.ISSUED, Status.ISSUE_FAILED);
        return this;
    }

    private void issueFailed(StripePurchase purchase, Reason reason) {
        metrics.record(Source.CARD, Status.ISSUE_FAILED, reason, purchase.getCurrency());
    }

    public PurchaseDischargeService(StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment) {
        this(purchases, fulfilment, Duration.ofMinutes(10));
    }

    public PurchaseDischargeService(
            StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment, Duration claimLease) {
        this(purchases, fulfilment, claimLease, DEFAULT_MAX_LEASE_EXPIRIES);
    }

    public PurchaseDischargeService(StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment,
            Duration claimLease, int maxLeaseExpiries) {
        this.purchases = purchases;
        this.fulfilment = fulfilment;
        this.claimLease = claimLease;
        this.maxLeaseExpiries = maxLeaseExpiries;
    }

    /**
     * Lapsed leases tolerated before a row is parked as stale_issuing (#273). Three lapses of a
     * 10-minute lease is half an hour of workers dying mid-mint: a person should look.
     */
    static final int DEFAULT_MAX_LEASE_EXPIRIES = 3;

    private final int maxLeaseExpiries;

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
        Optional<StripePurchase> found = purchases.findByEventId(eventId);
        if (found.isEmpty()) {
            return ClaimResult.UNKNOWN_PURCHASE;
        }
        Instant now = Instant.now();
        if (leaseLapsedTooOften(found.get(), now)) {
            park(found.get(), AttentionReason.STALE_ISSUING,
                    "claim lease lapsed " + found.get().getLeaseExpiries() + " times", null, now);
            log.warn("Purchase {} parked: claim lease lapsed {} times", eventId, found.get().getLeaseExpiries());
            return ClaimResult.NOT_CLAIMABLE;
        }
        String who = worker == null || worker.isBlank()
                ? "unnamed"
                : worker.substring(0, Math.min(128, worker.length()));
        int updated = purchases.claim(eventId, who, now, now.plus(claimLease),
                StripePurchase.Status.OWED, StripePurchase.Status.ISSUING);
        return updated == 1 ? ClaimResult.CLAIMED : ClaimResult.NOT_CLAIMABLE;
    }

    private boolean leaseLapsedTooOften(StripePurchase purchase, Instant now) {
        return purchase.getStatus() == StripePurchase.Status.ISSUING
                && purchase.getClaimedUntil() != null && purchase.getClaimedUntil().isBefore(now)
                && purchase.getLeaseExpiries() >= maxLeaseExpiries;
    }

    private void park(StripePurchase purchase, AttentionReason reason, String detail, String voucherId, Instant now) {
        purchase.setStatus(StripePurchase.Status.NEEDS_ATTENTION);
        purchase.setAttentionReason(reason);
        purchase.setAttentionDetail(truncate(detail));
        purchase.setAttentionAt(now);
        purchase.setClaimedUntil(null);
        if (voucherId != null && !voucherId.isBlank() && purchase.getVoucherId() == null) {
            purchase.setVoucherId(voucherId);
        }
        purchase.setUpdatedAt(now);
        purchases.save(purchase);
    }

    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(500, text.length()));
    }

    /** The answer to an attention report (#273). */
    public enum AttentionResult {
        /** Parked now. */
        RECORDED,
        /** Already parked; the first reason stands. Idempotent success. */
        ALREADY_RECORDED,
        /** Closed (DISCHARGED, REFUNDED), or held by another worker's live claim. */
        NOT_ALLOWED,
        /** A derived reason (refunded_after_issue, disputed) the issuer cannot report. */
        INVALID_REASON,
        UNKNOWN_PURCHASE
    }

    /**
     * The issuer reports that a purchase needs a person (#273): gc's 409 UNRECORDED, or a delivery
     * that gave up. The row leaves every automatic queue and never goes back to OWED by itself.
     *
     * @param worker    when set, a lapsed worker cannot park a row another worker now holds
     * @param voucherId the coupon that exists, when one does (delivery_exhausted)
     */
    @Transactional
    public AttentionResult reportAttention(String eventId, AttentionReason reason, String detail,
            String worker, String voucherId) {
        if (reason == null || !reason.parksTheRow()) {
            return AttentionResult.INVALID_REASON;
        }
        Optional<StripePurchase> found = purchases.findByEventId(eventId);
        if (found.isEmpty()) {
            return AttentionResult.UNKNOWN_PURCHASE;
        }
        StripePurchase purchase = found.get();
        Instant now = Instant.now();
        if (purchase.getStatus() == StripePurchase.Status.NEEDS_ATTENTION) {
            return AttentionResult.ALREADY_RECORDED;
        }
        if (purchase.getStatus() == StripePurchase.Status.DISCHARGED
                || purchase.getStatus() == StripePurchase.Status.REFUNDED
                || heldByAnother(purchase, worker, now)) {
            return AttentionResult.NOT_ALLOWED;
        }
        park(purchase, reason, detail, voucherId, now);
        log.warn("Purchase {} needs attention: {} ({})", eventId, reason.wire(), detail);
        return AttentionResult.RECORDED;
    }

    private static boolean heldByAnother(StripePurchase purchase, String worker, Instant now) {
        return purchase.getStatus() == StripePurchase.Status.ISSUING
                && worker != null && purchase.getClaimedBy() != null
                && !worker.equals(purchase.getClaimedBy())
                && purchase.getClaimedUntil() != null && purchase.getClaimedUntil().isAfter(now);
    }

    /** What an operator may do with a purchase (#273). There is no refund or revoke (#265). */
    public enum ResolveAction {
        /**
         * Put it back: OWED when no coupon exists, ISSUED (delivery and recheck) when one does.
         * Refused for gateway_unrecorded, whose mint state is unknown: use DISCHARGED once the
         * coupon is found, or leave it parked.
         */
        RETRY,
        /** Delivered by hand. Needs the coupon's id unless the row already carries one. */
        DISCHARGED,
        /** Seen the refund or dispute recorded after issue. Changes no status. */
        ACKNOWLEDGE;

        public static ResolveAction fromWire(String wire) {
            if (wire == null) {
                return null;
            }
            for (ResolveAction action : values()) {
                if (action.name().equalsIgnoreCase(wire.trim())) {
                    return action;
                }
            }
            return null;
        }
    }

    /** The answer to an operator's resolve (#273). */
    public enum ResolveResult {
        RESOLVED,
        /** The same resolution already took effect. Idempotent success. */
        ALREADY_RESOLVED,
        /** Not parked (or nothing to acknowledge), or a retry of gateway_unrecorded. */
        NOT_ALLOWED,
        /** DISCHARGED with no voucher to name. */
        INVALID,
        UNKNOWN_PURCHASE
    }

    /**
     * An operator moves a parked purchase on (#273). Only NEEDS_ATTENTION rows move; anything else
     * is refused, so this is never a back door around the claim or the discharge check.
     */
    @Transactional
    public ResolveResult resolve(String eventId, ResolveAction action, String voucherId,
            String operator, String note) {
        Optional<StripePurchase> found = purchases.findByEventId(eventId);
        if (found.isEmpty()) {
            return ResolveResult.UNKNOWN_PURCHASE;
        }
        StripePurchase purchase = found.get();
        Instant now = Instant.now();
        if (action == ResolveAction.ACKNOWLEDGE) {
            return acknowledgeReversal(purchase, operator, note, now);
        }
        if (purchase.getStatus() != StripePurchase.Status.NEEDS_ATTENTION) {
            return alreadyDone(purchase, action) ? ResolveResult.ALREADY_RESOLVED : ResolveResult.NOT_ALLOWED;
        }
        // A retry of gateway_unrecorded could mint a second coupon, so it is refused, UNLESS the
        // money went back: then retry means REFUNDED (below), which never mints. Without this a
        // refunded gateway_unrecorded row could never leave NEEDS_ATTENTION and its alert fired
        // forever (#219 review F5, fixed in #282).
        if (action == ResolveAction.RETRY
                && purchase.getAttentionReason() == AttentionReason.GATEWAY_UNRECORDED
                && !(purchase.getVoucherId() == null && wasRefunded(purchase))) {
            return ResolveResult.NOT_ALLOWED;
        }
        if (action == ResolveAction.DISCHARGED) {
            String voucher = voucherId != null && !voucherId.isBlank() ? voucherId : purchase.getVoucherId();
            if (voucher == null) {
                return ResolveResult.INVALID;
            }
            // The recorded id is the only handle on value already created (review of #280): an
            // operator naming a different coupon must not erase it from the trail.
            if (purchase.getVoucherId() != null && !purchase.getVoucherId().equals(voucher)) {
                return ResolveResult.INVALID;
            }
            purchase.setVoucherId(voucher);
            purchase.setStatus(StripePurchase.Status.DISCHARGED);
        } else if (purchase.getVoucherId() == null && wasRefunded(purchase)) {
            // Refunded while parked (review of #280): the listener leaves a parked row alone, so the
            // retry puts it where the automatic flow would have, REFUNDED. Never OWED: a refund
            // before issue never mints (#267).
            purchase.setStatus(StripePurchase.Status.REFUNDED);
        } else {
            purchase.setStatus(purchase.getVoucherId() == null
                    ? StripePurchase.Status.OWED : StripePurchase.Status.ISSUED);
            purchase.setLeaseExpiries(0);
        }
        log.warn("Purchase {} resolved by {}: {} from {} ({})", eventId, operator, action,
                purchase.getAttentionReason(), note);
        purchase.setAttentionReason(null);
        purchase.setAttentionDetail(null);
        purchase.setAttentionAt(null);
        recordResolution(purchase, operator, note, now);
        return ResolveResult.RESOLVED;
    }

    private static boolean wasRefunded(StripePurchase purchase) {
        return purchase.getRefundedAmountMinor() != null && purchase.getRefundedAmountMinor() > 0;
    }

    private static boolean alreadyDone(StripePurchase purchase, ResolveAction action) {
        return purchase.getResolvedAt() != null
                && (action == ResolveAction.DISCHARGED
                        ? purchase.getStatus() == StripePurchase.Status.DISCHARGED
                        : purchase.getStatus() == StripePurchase.Status.OWED
                                || purchase.getStatus() == StripePurchase.Status.ISSUING
                                || purchase.getStatus() == StripePurchase.Status.ISSUED
                                || purchase.getStatus() == StripePurchase.Status.REFUNDED);
    }

    private ResolveResult acknowledgeReversal(StripePurchase purchase, String operator, String note, Instant now) {
        boolean reversed = purchase.isDisputed()
                || (purchase.getRefundedAmountMinor() != null && purchase.getRefundedAmountMinor() > 0);
        if (!reversed || purchase.getReversedAt() == null) {
            return ResolveResult.NOT_ALLOWED;
        }
        if (purchase.getReversalAcknowledgedAt() != null
                && !purchase.getReversalAcknowledgedAt().isBefore(purchase.getReversedAt())) {
            return ResolveResult.ALREADY_RESOLVED;
        }
        purchase.setReversalAcknowledgedAt(now);
        log.warn("Purchase {} reversal acknowledged by {} (refunded {}, disputed {}): {}", purchase.getEventId(),
                operator, purchase.getRefundedAmountMinor(), purchase.isDisputed(), note);
        recordResolution(purchase, operator, note, now);
        return ResolveResult.RESOLVED;
    }

    private void recordResolution(StripePurchase purchase, String operator, String note, Instant now) {
        purchase.setResolvedBy(operator == null || operator.isBlank()
                ? "unnamed" : operator.substring(0, Math.min(128, operator.length())));
        purchase.setResolutionNote(truncate(note));
        purchase.setResolvedAt(now);
        purchase.setUpdatedAt(now);
        purchases.save(purchase);
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
        /**
         * The payment was refunded before issue (#267). Not closed: the coupon the issuer names
         * is kept on the row for an operator, and the issuer must not retry.
         */
        REFUNDED,
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

        // Refunded while a mint was in flight (#267). The coupon exists and has no money behind
        // it, so the row must not read as served; its id is kept for an operator.
        if (purchase.getStatus() == StripePurchase.Status.REFUNDED) {
            keepVoucherForRevocation(purchase, voucherId);
            log.warn("Refused discharge of {}: refunded before issue; voucher {} recorded",
                    eventId, voucherId);
            return Result.REFUNDED;
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
            // E4 (imani-wallet#289): the issuer delivered; what is missing is the gateway's
            // confirmation. Saying only "refused" reads as "the buyer has nothing".
            purchase.setLastFailure("voucher " + (voucherId == null ? "(unnamed)" : voucherId)
                    + " delivered, discharge not yet confirmed by the gateway");
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
            log.warn("Refused discharge of {}: no completed send answers its request", eventId);
            issueFailed(purchase, Reason.UNVERIFIABLE);
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
        // imani-wallet#287 (U4): the verdict must also count THIS row's coupon. A core from
        // before U1 names no coupons, which is unverifiable like the missing facts above.
        String pinned = purchase.getVoucherId() != null ? purchase.getVoucherId() : voucherId;
        String mismatch = mismatch(purchase, answer, fulfilment.publicKeyHex(), pinned);
        if (mismatch != null) {
            if (answer.recipientPubkey() == null || answer.amount() == null || answer.unit() == null
                    || answer.creatorPubkey() == null || answer.voucherIds() == null) {
                log.info("Gateway confirmed {} without the facts to check; leaving it owed", eventId);
                return Result.UNVERIFIABLE;
            }
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure("gateway answer does not match the purchase: " + mismatch);
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
            log.warn("Refused discharge of {}: {}", eventId, mismatch);
            issueFailed(purchase, Reason.UNVERIFIABLE);
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
            issueFailed(purchase, Reason.UNVERIFIABLE);
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
        // Once per purchase: ALREADY_DISCHARGED returns above, so a repeated discharge (or a
        // redelivered webhook, which never reaches this service) cannot count twice.
        metrics.record(Source.CARD, Status.ISSUED, Reason.NONE, purchase.getCurrency(), purchase.getAmountMinor());
        if (purchase.getCreatedAt() != null) {
            metrics.duration(Source.CARD, Stage.PAID_TO_ISSUED, Duration.between(purchase.getCreatedAt(), Instant.now()));
        }
        return Result.DISCHARGED;
    }

    /**
     * Why this answer cannot close this purchase, or null when it can.
     *
     * <p>Recipient, amount, unit and stall must all be the purchase's own. Any
     * of them missing is a reason too: the check is only as strong as its
     * weakest fact. So must the registering key: only terms this service
     * registered itself ({@code ownKey}) count. And the coupon: core must have counted
     * {@code voucherId}, the row's own (imani-wallet#287), so another coupon of the same
     * stall, buyer and face cannot close this debt.
     */
    static String mismatch(StripePurchase purchase, GatewayFulfilmentClient.Answer answer, String ownKey,
                           String voucherId) {
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
        if (answer.voucherIds() == null) {
            return "the answer does not name the coupons it counted";
        }
        if (voucherId == null || !answer.voucherIds().contains(voucherId)) {
            return "coupon " + voucherId + " is not among those counted " + answer.voucherIds();
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

    private void keepVoucherForRevocation(StripePurchase purchase, String voucherId) {
        if (voucherId != null && !voucherId.isBlank() && purchase.getVoucherId() == null) {
            purchase.setVoucherId(voucherId);
            purchase.setLastFailure("refunded before issue, but a coupon was minted while the mint was in flight");
            purchase.setUpdatedAt(Instant.now());
            purchases.save(purchase);
        }
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
            } else {
                // Re-read: discharge() may have written the row (attempts, a REFUNDED voucher
                // note), and saving this sweep's older copy would undo that (review of #278).
                purchases.findByEventId(row.getEventId())
                        .filter(fresh -> fresh.getStatus() == StripePurchase.Status.ISSUED)
                        .ifPresent(fresh -> {
                            fresh.setUpdatedAt(Instant.now());
                            purchases.save(fresh);
                        });
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
            if (purchase.getStatus() == StripePurchase.Status.REFUNDED) {
                // Terminal (#267). Never back to OWED; a named coupon is recorded.
                keepVoucherForRevocation(purchase, voucherId);
                return;
            }
            if (purchase.getStatus() == StripePurchase.Status.NEEDS_ATTENTION) {
                // Parked for a person (#273). Only /resolve moves it; a late report must not put
                // a row whose mint state is unknown back in the mintable queue. A coupon it names
                // is kept, though: for gateway_unrecorded it is the answer the operator needs
                // (review of #280).
                if (voucherId != null && !voucherId.isBlank() && purchase.getVoucherId() == null) {
                    purchase.setVoucherId(voucherId);
                    purchase.setUpdatedAt(Instant.now());
                    purchases.save(purchase);
                }
                log.warn("Failure report for parked purchase {} ignored for status", eventId);
                return;
            }
            purchase.setAttempts(purchase.getAttempts() + 1);
            purchase.setLastFailure(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
            // The reported text is free-form and never a label; only its shape picks the reason.
            issueFailed(purchase, permanent ? Reason.BLOCKED
                    : (voucherId != null && !voucherId.isBlank()) ? Reason.DELIVERY_FAILED : Reason.MINT_FAILED);

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
