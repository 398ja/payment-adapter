package xyz.tcheeric.payment.adapter.paypal.gateway.exception;

/** A request refused before PayPal is called: the counterpart of {@code StripeValidationException}. */
public class PaypalValidationException extends PaypalGatewayException {

    public PaypalValidationException(String message) {
        super(message);
    }
}
