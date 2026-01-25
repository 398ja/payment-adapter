package xyz.tcheeric.payment.adapter.webhook.spi;

import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import java.util.Map;

/**
 * Result of processing a webhook.
 *
 * @param processed  true if the webhook was processed (state changed), false if already processed (idempotent)
 * @param paymentId  the payment ID that was updated, or null if none
 * @param newState   the new state after processing, or current state if not changed
 * @param metadata   additional metadata from the webhook processing
 */
public record WebhookResult(
        boolean processed,
        String paymentId,
        State newState,
        Map<String, Object> metadata
) {
    /**
     * Creates a successful result indicating the webhook was processed.
     */
    public static WebhookResult success(String paymentId, State newState) {
        return new WebhookResult(true, paymentId, newState, Map.of());
    }

    /**
     * Creates a result indicating the webhook was already processed (duplicate).
     */
    public static WebhookResult duplicate(String paymentId, State currentState) {
        return new WebhookResult(false, paymentId, currentState, Map.of("duplicate", true));
    }
}
