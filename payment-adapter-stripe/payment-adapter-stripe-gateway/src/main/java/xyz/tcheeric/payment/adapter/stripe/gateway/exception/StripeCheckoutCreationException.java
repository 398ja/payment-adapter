package xyz.tcheeric.payment.adapter.stripe.gateway.exception;

public class StripeCheckoutCreationException extends StripeGatewayException {

    public StripeCheckoutCreationException(String quoteId, String currency, Throwable cause) {
        super("Failed to create Stripe checkout session for quoteId=" + quoteId + ", currency=" + currency, cause);
    }
}
