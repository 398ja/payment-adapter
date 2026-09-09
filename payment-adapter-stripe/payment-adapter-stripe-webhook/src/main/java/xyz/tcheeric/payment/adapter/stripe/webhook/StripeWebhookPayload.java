package xyz.tcheeric.payment.adapter.stripe.webhook;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StripeWebhookPayload implements WebhookPayload {

    private String eventId;
    private String eventType;
    private Instant eventTimestamp;
    private String rawPayload;
    private String quoteId;
    private String checkoutSessionId;
    private String paymentIntentId;
    private String chargeId;
    private Integer amountTotal;
    /** Refunded total in minor units (Stripe charge {@code amount_refunded}); set on charge.refunded. */
    private Integer refundedAmountMinor;
    private String currency;
    private boolean livemode;
    private String status;
    private String paymentStatus;

    /**
     * The connected account this event happened on, from the event's top-level
     * {@code account} field.
     *
     * <p>Present only for a DIRECT charge, which is what makes it the
     * discriminator between the two consequences a paid session can have: a
     * platform charge settles a mint quote, and an event carrying an account is
     * a stall selling a coupon. Stripe sets it on the event itself rather than
     * inside {@code data.object}, because it describes whose event it is.
     */
    private String connectedAccountId;

    /**
     * Everything the session carried, not just {@code quote_id}.
     *
     * <p>How a purchase names the buyer its coupon must reach, without a second
     * lookup and without the buyer typing a pubkey into a Stripe form.
     */
    private java.util.Map<String, String> metadata;

    @Override
    public String getIdempotencyKey() {
        return eventId;
    }
}
