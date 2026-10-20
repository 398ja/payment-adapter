package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

/** Stripe could not be read for a live payment check (#286). Callers fail closed: nothing moves. */
public class LivePaymentUnavailableException extends RuntimeException {

    public LivePaymentUnavailableException(String message) {
        super(message);
    }

    public LivePaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
