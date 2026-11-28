package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

/**
 * A plan event Imani cannot act on safely. Thrown so the webhook is NOT acknowledged: Stripe redelivers for days,
 * and an operator sees a failed event, where the alternative is a buyer who paid an instalment and holds nothing.
 */
public class RecurringPlanException extends RuntimeException {

    public RecurringPlanException(String message) {
        super(message);
    }

    public RecurringPlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
