package xyz.tcheeric.payment.adapter.paypal.gateway.exception;

/** Anything the PayPal gateway refuses or cannot do. The mirror of {@code StripeGatewayException}. */
public class PaypalGatewayException extends RuntimeException {

    public PaypalGatewayException(String message) {
        super(message);
    }

    public PaypalGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
