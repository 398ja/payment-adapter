package xyz.tcheeric.payment.adapter.paypal.gateway.exception;

/** An order could not be created for a purchase. The mirror of {@code StripeCheckoutCreationException}. */
public class PaypalCheckoutCreationException extends PaypalGatewayException {

    public PaypalCheckoutCreationException(String quoteId, String currency, Throwable cause) {
        super("Failed to create PayPal order for quoteId=" + quoteId + ", currency=" + currency, cause);
    }

    /** PayPal is down or slow (payment-adapter#274), so the buyer may retry; anything else is a refusal. */
    public boolean isPaypalUnavailable() {
        return getCause() instanceof PaypalApiException api && api.isUnavailable();
    }
}
