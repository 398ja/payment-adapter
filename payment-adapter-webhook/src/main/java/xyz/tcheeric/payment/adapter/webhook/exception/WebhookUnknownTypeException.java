package xyz.tcheeric.payment.adapter.webhook.exception;

/**
 * Thrown when no handler is registered for a webhook payment type.
 * Results in HTTP 404 Not Found.
 */
public final class WebhookUnknownTypeException extends WebhookException {

    private final String paymentType;

    public WebhookUnknownTypeException(String paymentType) {
        super("Unknown payment type: " + paymentType);
        this.paymentType = paymentType;
    }

    public String getPaymentType() {
        return paymentType;
    }
}
