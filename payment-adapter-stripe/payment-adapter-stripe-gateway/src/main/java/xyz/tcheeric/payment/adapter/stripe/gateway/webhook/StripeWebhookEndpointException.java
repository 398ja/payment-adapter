package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

/** A webhook endpoint could not be read from Stripe (#286). */
public class StripeWebhookEndpointException extends RuntimeException {

    public StripeWebhookEndpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
