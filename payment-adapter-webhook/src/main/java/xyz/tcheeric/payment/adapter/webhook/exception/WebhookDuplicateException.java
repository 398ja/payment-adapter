package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Thrown when a duplicate webhook is detected (already processed).
 * Results in HTTP 200 OK (idempotent success).
 */
public final class WebhookDuplicateException extends WebhookException {

    private final String idempotencyKey;

    public WebhookDuplicateException(String idempotencyKey) {
        super("Webhook already processed: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
