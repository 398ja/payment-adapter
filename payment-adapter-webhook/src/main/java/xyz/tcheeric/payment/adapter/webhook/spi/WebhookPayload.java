package xyz.tcheeric.payment.adapter.webhook.spi;

import java.time.Instant;

/**
 * Represents the parsed payload from a webhook request.
 * Each payment type (phoenixd, stripe, etc.) provides its own implementation.
 */
public interface WebhookPayload {

    /**
     * Returns a unique key for idempotency checking.
     * This key is used to detect and ignore duplicate webhook deliveries.
     *
     * @return unique idempotency key for this webhook event
     */
    String getIdempotencyKey();

    /**
     * Returns the event type (e.g., "payment_received", "charge.succeeded").
     *
     * @return the event type string
     */
    String getEventType();

    /**
     * Returns the timestamp of the event, if available.
     *
     * @return event timestamp, or current time if not provided
     */
    default Instant getEventTimestamp() {
        return Instant.now();
    }
}
