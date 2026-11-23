package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

/**
 * How many Stripe reads one reconcile run may make (#272). Stripe's read limit is shared with
 * checkout, so a run that finds a large backlog stops, keeps its cursor, and continues next run.
 */
public final class ReconcileBudget {

    private int remaining;
    private boolean rateLimited;

    public ReconcileBudget(int calls) {
        this.remaining = Math.max(0, calls);
    }

    /** Spend one call, or answer false when none is left (or Stripe said 429). */
    public boolean spend() {
        if (exhausted()) {
            return false;
        }
        remaining--;
        return true;
    }

    public boolean exhausted() {
        return rateLimited || remaining <= 0;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void rateLimited() {
        rateLimited = true;
    }
}
