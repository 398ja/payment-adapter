package xyz.tcheeric.payment.adapter.stripe.gateway.exception;

public class StripeCheckoutCreationException extends StripeGatewayException {

    public StripeCheckoutCreationException(String quoteId, String currency, Throwable cause) {
        super("Failed to create Stripe checkout session for quoteId=" + quoteId + ", currency=" + currency, cause);
    }

    /**
     * Stripe could not be reached or failed on its side (payment-adapter#274): a connection
     * failure or timeout, a 5xx, or a 429. The buyer can retry shortly. Anything else is a
     * refusal of the request itself, and retrying it unchanged cannot help.
     */
    public boolean isStripeUnavailable() {
        Throwable cause = getCause();
        if (cause instanceof com.stripe.exception.ApiConnectionException) {
            return true;
        }
        if (cause instanceof com.stripe.exception.StripeException stripe) {
            Integer status = stripe.getStatusCode();
            return status != null && (status >= 500 || status == 429);
        }
        return false;
    }
}
