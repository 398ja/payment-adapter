package xyz.tcheeric.payment.adapter.paypal.webhook;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every PayPal event type {@link PaypalWebhookHandler} acts on: {@code StripePaymentEvent}'s list,
 * in PayPal's words (ADR 0002). Dispatch is an exhaustive switch over this enum, and the payments
 * webhook in PayPal's dashboard must subscribe to exactly these. Any other type is acknowledged.
 */
public enum PaypalPaymentEvent {
    /** The buyer approved: capture now. Not yet paid, so not yet owed. */
    CHECKOUT_ORDER_APPROVED("CHECKOUT.ORDER.APPROVED"),
    /** The money moved: paid means owed. {@code checkout.session.completed} with {@code paid}. */
    PAYMENT_CAPTURE_COMPLETED("PAYMENT.CAPTURE.COMPLETED"),
    /** Counted as a failed card payment. */
    PAYMENT_CAPTURE_DECLINED("PAYMENT.CAPTURE.DECLINED"),
    /** Refund: before issue the row is REFUNDED, after issue the facts are recorded. */
    PAYMENT_CAPTURE_REFUNDED("PAYMENT.CAPTURE.REFUNDED"),
    /** PayPal took the money back outside a dispute: recorded as a full refund. */
    PAYMENT_CAPTURE_REVERSED("PAYMENT.CAPTURE.REVERSED"),
    /** Dispute opened: {@code charge.dispute.created}. */
    CUSTOMER_DISPUTE_CREATED("CUSTOMER.DISPUTE.CREATED"),
    /** Dispute ended: {@code charge.dispute.closed}. Seller-favoured restores the voucher. */
    CUSTOMER_DISPUTE_RESOLVED("CUSTOMER.DISPUTE.RESOLVED"),
    /** An instalment plan's subscription was approved and started (ADR 0003). Issues nothing. */
    BILLING_SUBSCRIPTION_ACTIVATED("BILLING.SUBSCRIPTION.ACTIVATED"),
    /** An instalment was paid: paid means owed, per instalment. Stripe's {@code invoice.paid}. */
    PAYMENT_SALE_COMPLETED("PAYMENT.SALE.COMPLETED"),
    /** An instalment was refunded. */
    PAYMENT_SALE_REFUNDED("PAYMENT.SALE.REFUNDED"),
    /** PayPal took an instalment back outside a dispute. */
    PAYMENT_SALE_REVERSED("PAYMENT.SALE.REVERSED"),
    /** An instalment failed. Stripe's {@code invoice.payment_failed}. */
    BILLING_SUBSCRIPTION_PAYMENT_FAILED("BILLING.SUBSCRIPTION.PAYMENT.FAILED"),
    /** PayPal stopped billing after repeated failures. */
    BILLING_SUBSCRIPTION_SUSPENDED("BILLING.SUBSCRIPTION.SUSPENDED"),
    /** The buyer, the stall or Imani ended the plan. */
    BILLING_SUBSCRIPTION_CANCELLED("BILLING.SUBSCRIPTION.CANCELLED"),
    /** A fixed-term plan ran its course: every instalment was billed. */
    BILLING_SUBSCRIPTION_EXPIRED("BILLING.SUBSCRIPTION.EXPIRED");

    private final String type;

    PaypalPaymentEvent(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static Optional<PaypalPaymentEvent> fromType(String eventType) {
        return Arrays.stream(values()).filter(event -> event.type.equals(eventType)).findFirst();
    }

    public static Set<String> types() {
        return Arrays.stream(values()).map(PaypalPaymentEvent::type).collect(Collectors.toUnmodifiableSet());
    }
}
