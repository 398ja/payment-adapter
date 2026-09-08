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
        @Index(name = "idx_stripe_purchase_session", columnList = "checkout_session_id"),
        @Index(name = "idx_stripe_purchase_status", columnList = "status")
})
public class StripePurchase {

    /** What still has to happen to this purchase. */
    public enum Status {
        /** Paid, undelivered. The customer is owed a coupon. */
        OWED,
        /** A coupon was issued and reached the buyer. */
        DISCHARGED,
        /**
         * Something is wrong that retrying will not fix: a burnt credential, a
         * stall that no longer exists. Needs a person, and stays visible until
         * one arrives.
         */
        BLOCKED
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

    @Column(name = "checkout_session_id", nullable = false)
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

    /** The stall that was paid, and that owes the coupon. */
    @Column(name = "connected_account_id", nullable = false)
    private String connectedAccountId;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
