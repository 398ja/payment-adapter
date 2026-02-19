package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Thrown when a webhook request cannot be parsed.
 * Results in HTTP 400 Bad Request.
 */
public final class WebhookParseException extends WebhookException {

    public WebhookParseException(String message) {
        super(message);
    }

    public WebhookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
