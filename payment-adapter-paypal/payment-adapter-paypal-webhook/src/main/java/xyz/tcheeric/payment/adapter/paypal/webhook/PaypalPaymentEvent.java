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
    CUSTOMER_DISPUTE_RESOLVED("CUSTOMER.DISPUTE.RESOLVED");

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
