package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

/**
 * Stripe could not be read for reconciliation. The run stops for that account and its cursor does
 * not move, so nothing is skipped; {@link #isRateLimited()} stops the whole run.
 */
public class StripeReconcileException extends RuntimeException {

    private final boolean rateLimited;

    public StripeReconcileException(String message, Throwable cause, boolean rateLimited) {
        super(message, cause);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
