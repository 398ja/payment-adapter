package xyz.tcheeric.payment.adapter.paypal.gateway.webhook;

/** A webhook delivery that cannot be shown to be PayPal's, for our webhook. */
public class PaypalSignatureException extends RuntimeException {

    public PaypalSignatureException(String message) {
        super(message);
    }

    public PaypalSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
