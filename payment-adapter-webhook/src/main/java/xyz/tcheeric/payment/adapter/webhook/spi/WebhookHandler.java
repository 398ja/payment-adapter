package xyz.tcheeric.payment.adapter.webhook.spi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;

/**
 * SPI for handling webhooks from different payment providers.
 * Each payment type (phoenixd, stripe, etc.) provides its own implementation.
 *
 * @param <T> the payload type this handler processes
 */
public interface WebhookHandler<T extends WebhookPayload> {

    /**
     * Returns the payment type identifier this handler supports.
     * This is used for routing webhooks to the correct handler.
     *
     * @return payment type identifier (e.g., "phoenixd", "stripe")
     */
    String getPaymentType();

    /**
     * Parses the raw HTTP request into a typed payload.
     *
     * @param request the incoming HTTP request
     * @return parsed payload
     * @throws WebhookParseException if the request cannot be parsed
     */
    T parsePayload(HttpServletRequest request) throws WebhookParseException;

    /**
     * Validates the webhook signature or authentication.
     * Some providers (like phoenixd) may not use signatures.
     *
     * @param payload the parsed payload
     * @param request the original HTTP request (for headers)
     * @throws WebhookSignatureException if signature validation fails
     */
    default void validateSignature(T payload, HttpServletRequest request) throws WebhookSignatureException {
        // Default: no signature validation
    }

    /**
     * Processes the webhook and returns the result.
     * This method should be idempotent - calling it multiple times with
     * the same payload should produce the same result.
     *
     * @param payload the validated payload
     * @return result of processing
     * @throws WebhookProcessingException if processing fails
     * @throws WebhookDuplicateException if this webhook was already processed
     */
    WebhookResult handle(T payload) throws WebhookProcessingException, WebhookDuplicateException;

    /**
     * Maps an exception to an HTTP status code.
     *
     * @param e the exception
     * @return appropriate HTTP status code
     */
    default int getErrorStatusCode(WebhookException e) {
        return switch (e) {
            case WebhookParseException ignored -> HttpServletResponse.SC_BAD_REQUEST;
            case WebhookSignatureException ignored -> HttpServletResponse.SC_UNAUTHORIZED;
            case WebhookDuplicateException ignored -> HttpServletResponse.SC_OK;
            case WebhookProcessingException ignored -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
    }
}
