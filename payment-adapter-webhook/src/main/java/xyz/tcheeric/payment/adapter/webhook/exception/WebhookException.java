package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Base exception for all webhook processing errors.
 * Sealed to ensure exhaustive handling in switch expressions.
 */
public sealed class WebhookException extends RuntimeException
        permits WebhookParseException, WebhookSignatureException,
                WebhookDuplicateException, WebhookProcessingException,
                WebhookUnknownTypeException {

    public WebhookException(String message) {
        super(message);
    }

    public WebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
