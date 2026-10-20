package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

/**
 * Why an operator's {@code retry} was refused by the live check (#286), in terms the operator can act
 * on. Serialised as the body of the 409.
 *
 * @param error  always {@code retry_refused}
 * @param reason the wire reason, see {@link Reason}
 * @param detail what Stripe said, and what to do instead
 */
public record RetryRefusal(String error, String reason, String detail) {

    static final String ERROR = "retry_refused";

    static RetryRefusal of(Reason reason, String detail) {
        return new RetryRefusal(ERROR, reason.wire(), detail);
    }

    /** The reasons a live check refuses a retry. */
    public enum Reason {
        /** The charge is disputed. A dispute can still be lost (#265): never mint or deliver on one. */
        DISPUTED,
        /** A coupon exists and the money went back. Retrying would deliver a coupon nobody paid for. */
        REFUNDED_AFTER_ISSUE,
        /** No coupon, and Stripe shows a refund the row did not know about. Now recorded. */
        REFUNDED,
        /** The row names no PaymentIntent, so there is nothing to read: fail closed. */
        NO_PAYMENT_INTENT;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
