package xyz.tcheeric.payment.adapter.stripe.webhook;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every Stripe event type {@link StripeWebhookHandler} acts on (payment-adapter#286). The handler's
 * dispatch is an exhaustive switch over this enum, so a type handled there is listed here, and the
 * webhook subscription check asks Stripe for exactly this list: adding a handled event adds it to the
 * check, with no second list to keep in step. Any other type is acknowledged and ignored.
 */
public enum StripePaymentEvent {
    /** Mint quote paid, or a coupon purchase recorded as owed. */
    CHECKOUT_SESSION_COMPLETED("checkout.session.completed"),
    CHECKOUT_SESSION_ASYNC_PAYMENT_SUCCEEDED("checkout.session.async_payment_succeeded"),
    /** Counted as a failed card payment. */
    CHECKOUT_SESSION_ASYNC_PAYMENT_FAILED("checkout.session.async_payment_failed"),
    PAYMENT_INTENT_PAYMENT_FAILED("payment_intent.payment_failed"),
    /** Counted as a cancelled card payment; StripeWebhookSilent relies on it. */
    CHECKOUT_SESSION_EXPIRED("checkout.session.expired"),
    /** Refund (#278): before issue the row is REFUNDED, after issue the facts are recorded. */
    CHARGE_REFUNDED("charge.refunded"),
    /** Dispute (#278). */
    CHARGE_DISPUTE_CREATED("charge.dispute.created"),
    /** A dispute ended (imani-wallet#277): won restores the voucher, anything else keeps it blocked. */
    CHARGE_DISPUTE_CLOSED("charge.dispute.closed"),
    /** An instalment of a recurring plan was paid: issues that instalment's vouchers. Only when stripe.recurring is on. */
    INVOICE_PAID("invoice.paid", true),
    /** An instalment failed; Stripe retries. The plan goes past due and nothing is issued. */
    INVOICE_PAYMENT_FAILED("invoice.payment_failed", true),
    /** A plan's subscription ended, by completion or cancellation. */
    CUSTOMER_SUBSCRIPTION_DELETED("customer.subscription.deleted", true);

    private final String type;
    /** True for the events only instalment plans need; the endpoint must deliver them only when plans are on. */
    private final boolean plan;

    StripePaymentEvent(String type) {
        this(type, false);
    }

    StripePaymentEvent(String type, boolean plan) {
        this.type = type;
        this.plan = plan;
    }

    /** Stripe's event type, e.g. {@code charge.refunded}. */
    public String type() {
        return type;
    }

    public boolean is(String eventType) {
        return type.equals(eventType);
    }

    public static Optional<StripePaymentEvent> fromType(String eventType) {
        return Arrays.stream(values()).filter(event -> event.is(eventType)).findFirst();
    }

    /** What the payments endpoint must subscribe to when instalment plans are off. */
    public static Set<String> types() {
        return types(false);
    }

    /**
     * What the payments endpoint must subscribe to. The plan events are required only when plans are on, so a
     * deployment that has not enabled them is not told its endpoint is missing events it never uses.
     */
    public static Set<String> types(boolean plansEnabled) {
        return Arrays.stream(values()).filter(event -> plansEnabled || !event.plan)
                .map(StripePaymentEvent::type).collect(Collectors.toUnmodifiableSet());
    }
}
