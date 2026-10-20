package xyz.tcheeric.payment.adapter.stripe.connect;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every Stripe event type {@link StripeConnectService#handleWebhook} acts on (payment-adapter#286). Its
 * dispatch is an exhaustive switch over this enum, and the webhook subscription check asks Stripe for
 * exactly this list. Any other type is acknowledged and ignored.
 */
public enum StripeConnectEvent {
    ACCOUNT_UPDATED("account.updated"),
    ACCOUNT_APPLICATION_DEAUTHORIZED("account.application.deauthorized");

    private final String type;

    StripeConnectEvent(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static Optional<StripeConnectEvent> fromType(String eventType) {
        return Arrays.stream(values()).filter(event -> event.type.equals(eventType)).findFirst();
    }

    /** What the Connect endpoint must subscribe to. */
    public static Set<String> types() {
        return Arrays.stream(values()).map(StripeConnectEvent::type).collect(Collectors.toUnmodifiableSet());
    }
}
