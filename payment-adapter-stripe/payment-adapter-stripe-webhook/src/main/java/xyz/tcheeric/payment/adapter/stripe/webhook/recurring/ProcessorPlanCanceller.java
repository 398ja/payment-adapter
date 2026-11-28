package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;

/**
 * Ends a plan that another processor (not Stripe) runs, for {@link PlanManagementController}'s buyer cancel. The
 * plan list and cancel routes are shared by every processor, because the wallet shows one list; only the call that
 * stops the money differs. Implemented by the PayPal module (ADR 0003).
 */
public interface ProcessorPlanCanceller {

    /** The {@link RecurringPlan#getProcessor()} this canceller serves, e.g. {@code paypal}. */
    String processor();

    /**
     * Stops all further instalments. Instalments already paid are untouched. Idempotent.
     *
     * @throws RuntimeException when the processor could not be asked, so the buyer can try again
     */
    void cancel(RecurringPlan plan);
}
