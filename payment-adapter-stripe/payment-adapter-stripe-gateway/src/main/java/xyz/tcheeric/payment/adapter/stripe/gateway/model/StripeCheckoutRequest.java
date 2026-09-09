package xyz.tcheeric.payment.adapter.stripe.gateway.model;

import lombok.Builder;
import lombok.Value;

/**
 * One Checkout Session to create.
 *
 * <p>Two shapes travel through this type, and the difference is where the money
 * lands.
 *
 * <p><b>A platform charge</b> leaves {@link #connectedAccountId} null. Funds go
 * to the platform account, which is correct for the mint-quote flow this class
 * was written for: Imani is selling, so Imani is merchant of record.
 *
 * <p><b>A direct charge</b> sets it, and the session is created ON the stall's
 * connected account. The stall is merchant of record, the funds never touch a
 * platform balance, and the platform's cut is expressed as
 * {@link #applicationFeeMinor}. That is the shape a coupon purchase must take:
 * a platform charge there would make Imani the holder of customer money and put
 * it inside the FCA safeguarding perimeter, which the whole no-float design
 * exists to avoid.
 *
 * <p>Nullable rather than a subtype because the difference is two request
 * fields, and a hierarchy would put a type boundary where a null check does the
 * job. See {@code docs/adr/0001-coupon-purchases-are-direct-charges.md}.
 */
@Value
@Builder
public class StripeCheckoutRequest {
    String quoteId;
    Integer amount;
    String description;
    String currency;
    String idempotencyKey;

    /**
     * The connected account to charge on, or null for a platform charge.
     *
     * <p>An account id (<code>acct_…</code>), never a credential: acting on a
     * connected account uses the platform key plus this id, which is why no
     * stall ever hands over a Stripe secret.
     */
    String connectedAccountId;

    /**
     * The platform's cut, in minor units, or null to take nothing.
     *
     * <p>Only meaningful on a direct charge. Stripe rejects an application fee
     * on a session that names no connected account, so the two travel together
     * and are validated together.
     */
    Long applicationFeeMinor;

    /**
     * Free-form key/values carried on the session and returned on the webhook.
     *
     * <p>How a purchase names its buyer without a second lookup: the pubkey the
     * coupon must be issued to rides here, validated before the card is charged
     * rather than typed into a Stripe form.
     */
    @lombok.Singular("metadata")
    java.util.Map<String, String> metadata;

    /** Whether this session charges a connected account rather than the platform. */
    public boolean isDirectCharge() {
        return connectedAccountId != null && !connectedAccountId.isBlank();
    }
}
