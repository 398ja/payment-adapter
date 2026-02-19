package xyz.tcheeric.payment.adapter.ln.webhook;

import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;

import java.time.Instant;

/**
 * Payload for Phoenixd webhook requests.
 */
public record PhoenixWebhookPayload(
        String type,
        Integer amountSat,
        String paymentHash,
        String externalId
) implements WebhookPayload {

    @Override
    public String getIdempotencyKey() {
        // Combine externalId and paymentHash for uniqueness
        String hash = paymentHash != null ? paymentHash : "unknown";
        return "phoenixd:" + externalId + ":" + hash;
    }

    @Override
    public String getEventType() {
        return type;
    }

    @Override
    public Instant getEventTimestamp() {
        // Phoenixd doesn't provide timestamp, use current time
        return Instant.now();
    }
}
