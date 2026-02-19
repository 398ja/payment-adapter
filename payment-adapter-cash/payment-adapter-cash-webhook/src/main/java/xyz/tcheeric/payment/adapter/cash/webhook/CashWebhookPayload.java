package xyz.tcheeric.payment.adapter.cash.webhook;

import lombok.Builder;
import lombok.Data;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;

import java.time.Instant;

/**
 * Webhook payload for cash payment intents (NIP-XX kind 5201).
 * This is received when a customer sends a CashIntent to the merchant's relay subscription.
 */
@Data
@Builder
public class CashWebhookPayload implements WebhookPayload {

    /**
     * Nostr event ID (used as idempotency key)
     */
    private final String eventId;

    /**
     * Event kind (should be 5201 for CashIntent)
     */
    private final Integer kind;

    /**
     * Invoice reference from the intent
     */
    private final String ref;

    /**
     * Customer's ephemeral public key
     */
    private final String customerPubkey;

    /**
     * Optional proof code from customer
     */
    private final String proof;

    /**
     * Customer's timestamp from the intent
     */
    private final Long customerTimestamp;

    /**
     * Event signature (hex)
     */
    private final String signature;

    /**
     * Raw event JSON for signature verification
     */
    private final String rawEvent;

    /**
     * Decrypted content payload
     */
    private final String decryptedContent;

    /**
     * Timestamp when webhook was received
     */
    @Builder.Default
    private final Instant receivedAt = Instant.now();

    @Override
    public String getIdempotencyKey() {
        // Use event ID as unique key - Nostr events are immutable
        return eventId;
    }

    @Override
    public String getEventType() {
        return "cash.intent." + kind;
    }

    @Override
    public Instant getEventTimestamp() {
        return customerTimestamp != null
                ? Instant.ofEpochSecond(customerTimestamp)
                : receivedAt;
    }
}
