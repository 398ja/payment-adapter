package xyz.tcheeric.payment.adapter.webhook.core;

import jakarta.servlet.http.HttpServletResponse;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.util.Map;

/**
 * Response from webhook processing, including HTTP status code.
 *
 * @param statusCode HTTP status code
 * @param success    true if webhook was processed successfully
 * @param message    status message
 * @param result     the processing result (may be null on error)
 */
public record WebhookResponse(
        int statusCode,
        boolean success,
        String message,
        WebhookResult result
) {

    /**
     * Creates a successful response.
     */
    public static WebhookResponse success(WebhookResult result) {
        return new WebhookResponse(
                HttpServletResponse.SC_CREATED,
                true,
                "Webhook processed successfully",
                result
        );
    }

    /**
     * Creates a duplicate (idempotent success) response.
     */
    public static WebhookResponse duplicate(String idempotencyKey) {
        return new WebhookResponse(
                HttpServletResponse.SC_OK,
                true,
                "Webhook already processed: " + idempotencyKey,
                null
        );
    }

    /**
     * Creates an error response.
     */
    public static WebhookResponse error(int statusCode, String message) {
        return new WebhookResponse(statusCode, false, message, null);
    }

    /**
     * Converts this response to a JSON-like map for serialization.
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "success", success,
                "message", message,
                "statusCode", statusCode
        );
    }
}
