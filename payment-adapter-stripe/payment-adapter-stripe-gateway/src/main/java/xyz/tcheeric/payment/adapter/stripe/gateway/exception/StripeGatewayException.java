package xyz.tcheeric.payment.adapter.stripe.gateway.exception;

public class StripeGatewayException extends RuntimeException {

    public StripeGatewayException(String message) {
        super(message);
    }

    public StripeGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
