package xyz.tcheeric.payment.adapter.stripe.gateway.exception;

public class StripeValidationException extends StripeGatewayException {

    public StripeValidationException(String message) {
        super(message);
    }
}
