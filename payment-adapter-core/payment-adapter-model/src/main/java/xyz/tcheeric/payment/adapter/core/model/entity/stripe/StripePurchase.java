package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A coupon purchase that was paid for and has not yet been delivered.
 *
 * <p>This is the durable half of "paid means owed". A webhook records one
 * transactionally with accepting the payment, and something else works it off
 * later. Until that happens the customer has paid and holds nothing, which is
 * recoverable precisely because this row exists.
 *
 * <h2>Why a payments service holds this at all</h2>
 *
 * <p>An Obligation is a coupon concept, and coupons are not this service's
 * business. But the row has to be written in the same breath as accepting the
 * webhook, or the guarantee is not a guarantee, and only this service is in
 * that breath.
 *
 * <p>So the boundary is drawn at meaning rather than at storage. This table
 * records <b>that a purchase was paid and is undischarged</b>, which is a
 * payments fact. It holds no coupon, no token, no face value in coupon terms,
 * and no opinion about what discharging involves. The issuer decides that, and
 * reports back only whether it happened.
 *
 * <p>The name says the same thing: {@code stripe_purchase}, not
 * {@code obligation}. A reader looking for coupon logic will not find it here.
 *
 * <h2>What must never happen to a row</h2>
 *
 * <p><b>It must not be deleted to mean "done".</b> A discharged purchase is
 * evidence that a customer was served, and the row is the only place that
 * evidence lives on this side.
 *
 * <p><b>It must not be marked discharged on an attempt.</b> Only on confirmed
 * issuance. That rule is the whole of {@code imani-woo} ADR 0009 and it is why
 * {@link #voucherId} exists: a discharge names the coupon that discharged it.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "stripe_purchase", indexes = {
        @Index(name = "idx_stripe_purchase_event_id", columnList = "event_id", unique = true),
        @Index(name = "uq_stripe_purchase_session", columnList = "checkout_session_id", unique = true),
        @Index(name = "uq_stripe_purchase_payment_intent", columnList = "payment_intent_id", unique = true),
        @Index(name = "idx_stripe_purchase_status", columnList = "status")
})
public class StripePurchase {

    /** What still has to happen to this purchase. */
    public enum Status {
        /** Paid, and nothing minted yet. The only state safe to mint from. */
        OWED,

        /**
         * One worker has claimed this and may be minting right now
         * (imani-wallet#96).
         *
         * <p>Held under a lease ({@link #claimedUntil}). Not in the owed queue,
         * so a second worker cannot mint for it. A worker that dies holding the
         * claim leaves it here until the lease expires, and the re-claim mints
         * under the same idempotency key, so the gateway returns the original
         * coupon rather than a new one.
         */
        ISSUING,

        /**
         * A coupon EXISTS for this purchase and the debt is not closed.
         *
         * <p>The state that stops one payment becoming many coupons. Delivery
         * failed, or the discharge could not be confirmed, and both leave value
         * already created. Left OWED, the next pass would read it as unminted
         * and mint again — one row, unbounded coupons, on a loop that runs
         * every time the worker wakes.
         *
         * <p>Nothing automatic leaves this state. Closing it means confirming
         * the coupon that already exists reached the buyer, which is a
         * discharge; minting another one is never the answer.
         */
        ISSUED,
        /** A coupon was issued and reached the buyer. */
        DISCHARGED,
        /**
         * Something is wrong that retrying will not fix: a burnt credential, a
         * stall that no longer exists. Needs a person, and stays visible until
         * one arrives.
         */
        BLOCKED,
        /**
         * The money went back before any coupon existed (payment-adapter#267,
         * policy on #265). Terminal: never claimed, never minted, never
         * discharged. A coupon that lands anyway (a mint already in flight)
         * is recorded in {@link #voucherId} for a person to see, and the status stays.
         */
        REFUNDED,
        /**
         * Nothing automatic can safely move this on (payment-adapter#273). The reason is in
         * {@link #attentionReason}. Never claimed, never listed as owed; only an operator's
         * {@code /resolve} moves it, back to OWED (nothing minted), ISSUED (a coupon exists), or
         * DISCHARGED (delivered by hand).
         */
        NEEDS_ATTENTION
    }

    /**
     * Why a purchase needs a person (payment-adapter#273). The wire name ({@link #wire()}) is the
     * API value and the {@code reason} label of {@code imani_payments_card_needs_attention}.
     */
    public enum AttentionReason {
        /**
         * gc answered 409 UNRECORDED for the delegated-warrant nonce (gc#232): a first run never
         * completed, so whether a coupon was minted is unknown. Never retried automatically.
         */
        GATEWAY_UNRECORDED(true),
        /** The issuer's delivery outbox gave up (imani-wallet#274, U8). A coupon exists. */
        DELIVERY_EXHAUSTED(true),
        /** The claim lease lapsed too many times: workers keep dying mid-mint. */
        STALE_ISSUING(true),
        /** Refunded after a coupon exists. Informational: refunds are not supported (#265). */
        REFUNDED_AFTER_ISSUE(false),
        /** Disputed. Informational: disputes are not supported, nothing is revoked (#265). */
        DISPUTED(false);

        private final boolean parksTheRow;

        AttentionReason(boolean parksTheRow) {
            this.parksTheRow = parksTheRow;
        }

        /**
         * True when the reason is a status (the row is NEEDS_ATTENTION); false when it is derived
         * from Stripe facts on a row whose status does not change.
         */
        public boolean parksTheRow() {
            return parksTheRow;
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The reason for a wire name, or null when there is none. */
        public static AttentionReason fromWire(String wire) {
            if (wire == null) {
                return null;
            }
            for (AttentionReason reason : values()) {
                if (reason.wire().equals(wire.trim().toLowerCase(java.util.Locale.ROOT))) {
                    return reason;
                }
            }
            return null;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Stripe's event id, and the idempotency key for the whole flow.
     *
     * <p>Unique, because Stripe delivers more than once and a coupon cannot be
     * un-minted. The uniqueness constraint is the last line of defence: it
     * turns a duplicate into a database error rather than a second coupon.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    /**
     * The Checkout Session that took the money. One payment, one row (V14): a session paid once is
     * never two debts, whatever events announce it.
     */
    @Column(name = "checkout_session_id", nullable = false, unique = true)
    private String checkoutSessionId;

    /**
     * The id a discharge is verified against.
     *
     * <p>Generated here, when the debt is recorded, and stamped by the issuer
     * onto the send it makes. {@code gateway-core} can then answer whether a
     * COMPLETED send answered it, which turns a discharge from the issuer's
     * word into something checkable — {@code imani-woo} ADR 0009's
     * "fulfilment is confirmed, not asserted".
     *
     * <p>Minted by the party owed an answer, BEFORE the work happens, so it
     * cannot be chosen afterwards to fit whatever occurred. An id the issuer
     * generated at delivery time would verify only that the issuer did
     * something, which is what it already claims.
     */
    @Column(name = "payment_request_id", unique = true)
    private String paymentRequestId;

    @Column(name = "payment_intent_id")
    private String paymentIntentId;

    /** The stall's Stripe account, which was paid. */
    @Column(name = "connected_account_id", nullable = false)
    private String connectedAccountId;

    /**
     * The stall's NOSTR pubkey, which is a different thing entirely.
     *
     * <p>Both identify the same stall and neither substitutes for the other: an
     * {@code acct_…} addresses money, and a pubkey addresses issuance. The
     * issuer looks up a stall's credential by pubkey, so a row carrying only
     * the Stripe id would find no credential and fall back to manual issuance
     * for every card sale — the feature silently doing nothing.
     */
    @Column(name = "stall_pubkey")
    private String stallPubkey;

    /**
     * Who the coupon is for, as a Nostr pubkey, or null when the buyer had no
     * wallet and takes delivery by claim link instead.
     */
    @Column(name = "recipient_pubkey")
    private String recipientPubkey;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    /**
     * Test-mode traffic, kept rather than discarded.
     *
     * <p>An issuer must refuse to mint real value from a test payment, and it
     * can only refuse if it is told. Dropping these rows at the webhook would
     * hide a misconfigured deployment instead of making it visible.
     */
    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    /**
     * The coupon that discharged this, set only when {@link Status#DISCHARGED}.
     *
     * <p>Present so a discharge is evidence rather than an assertion. A row
     * marked discharged with no voucher id is a bug, and one that can be
     * detected after the fact.
     */
    @Column(name = "voucher_id")
    private String voucherId;

    /**
     * Why this is not discharged yet, in words an operator can act on.
     *
     * <p>Overwritten on each attempt rather than accumulated: the current
     * reason is what matters, and an unbounded log column is how a row becomes
     * unreadable.
     */
    @Column(name = "last_failure", length = 500)
    private String lastFailure;

    /**
     * How many times issuance has been tried.
     *
     * <p>Not a retry limit. Nothing gives up on a debt; this exists so that a
     * purchase failing forever can be found and looked at, which a timestamp
     * alone makes harder.
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Until when the current claim holds, set with {@link Status#ISSUING}.
     *
     * <p>Past it, the claim is abandoned (the worker died) and the row may be
     * claimed again. Null outside ISSUING.
     */
    @Column(name = "claimed_until")
    private Instant claimedUntil;

    /** Which worker holds the claim, for an operator reading the row. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    /**
     * Cumulative {@code amount_refunded} Stripe reported, in minor units; null when never refunded.
     *
     * <p>Kept on every row, issued or not. Refunds are not supported yet (#265): after issue this is
     * a record only, so backed = paid - refunded stays derivable.
     */
    @Column(name = "refunded_amount_minor")
    private Long refundedAmountMinor;

    /**
     * The buyer opened a dispute. Recorded only: nothing acts on it (#265).
     */
    @Column(name = "disputed", nullable = false)
    private boolean disputed;

    /** When the latest refund or dispute was recorded. */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** Why this is NEEDS_ATTENTION; null otherwise, and cleared on resolve (#273). */
    @Enumerated(EnumType.STRING)
    @Column(name = "attention_reason", length = 32)
    private AttentionReason attentionReason;

    /** What the reporter said, for the operator. */
    @Column(name = "attention_detail", length = 500)
    private String attentionDetail;

    /** When the row was parked. */
    @Column(name = "attention_at")
    private Instant attentionAt;

    /**
     * How many times a lapsed claim was taken over (#273). Past the limit the claim parks the row
     * as {@link AttentionReason#STALE_ISSUING} instead of handing it out again.
     */
    @Column(name = "lease_expiries", nullable = false)
    private int leaseExpiries;

    /** Who last resolved an attention row, and what they said: the audit trail of a manual move. */
    @Column(name = "resolved_by", length = 128)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * When an operator acknowledged the latest refund or dispute after issue. A later reversal
     * ({@link #reversedAt} past this) raises it again.
     */
    @Column(name = "reversal_acknowledged_at")
    private Instant reversalAcknowledgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock (review of #278). Every writer saves the whole row, and a discharge holds
     * its copy for seconds while it asks the gateway. Without this, a refund recorded meanwhile
     * (REFUNDED, already acknowledged to Stripe) is silently overwritten by the stale copy, or a
     * refund handler's stale copy erases a discharge. The late writer now fails and retries.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
