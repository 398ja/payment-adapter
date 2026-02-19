package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Thrown when webhook processing fails due to business logic errors.
 * Results in HTTP 500 Internal Server Error.
 */
public final class WebhookProcessingException extends WebhookException {

    public WebhookProcessingException(String message) {
        super(message);
    }

    public WebhookProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
