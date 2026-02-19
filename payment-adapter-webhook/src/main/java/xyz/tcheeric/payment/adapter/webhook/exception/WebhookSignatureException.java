package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Thrown when webhook signature validation fails.
 * Results in HTTP 401 Unauthorized.
 */
public final class WebhookSignatureException extends WebhookException {

    public WebhookSignatureException(String message) {
        super(message);
    }

    public WebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
