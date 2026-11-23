package xyz.tcheeric.payment.adapter.paypal.connect;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every PayPal event type the onboarding webhook acts on, the counterpart of
 * {@code StripeConnectEvent}. Dispatch is an exhaustive switch over this enum, and the webhook in
 * PayPal's dashboard must subscribe to exactly these. Any other type is acknowledged and ignored.
 */
public enum PaypalConnectEvent {
    /** The seller finished PayPal's flow: their merchant id now exists. */
    MERCHANT_ONBOARDING_COMPLETED("MERCHANT.ONBOARDING.COMPLETED"),
    /** The seller confirmed their email: the last thing between onboarding and taking cards. */
    SELLER_EMAIL_CONFIRMED("CUSTOMER.MERCHANT-INTEGRATION.SELLER-EMAIL-CONFIRMED"),
    /** A capability changed, in either direction: the counterpart of {@code account.updated}. */
    CAPABILITY_UPDATED("CUSTOMER.MERCHANT-INTEGRATION.CAPABILITY-UPDATED"),
    /** The seller revoked this platform's permissions: {@code account.application.deauthorized}. */
    PARTNER_CONSENT_REVOKED("MERCHANT.PARTNER-CONSENT.REVOKED");

    private final String type;

    PaypalConnectEvent(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static Optional<PaypalConnectEvent> fromType(String eventType) {
        return Arrays.stream(values()).filter(event -> event.type.equals(eventType)).findFirst();
    }

    public static Set<String> types() {
        return Arrays.stream(values()).map(PaypalConnectEvent::type).collect(Collectors.toUnmodifiableSet());
    }
}
