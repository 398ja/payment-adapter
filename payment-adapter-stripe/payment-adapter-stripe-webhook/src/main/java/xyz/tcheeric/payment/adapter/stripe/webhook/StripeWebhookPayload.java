package xyz.tcheeric.payment.adapter.stripe.webhook;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;

@Data
@Builder
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

    @Override
    public String getIdempotencyKey() {
        return eventId;
    }
}
