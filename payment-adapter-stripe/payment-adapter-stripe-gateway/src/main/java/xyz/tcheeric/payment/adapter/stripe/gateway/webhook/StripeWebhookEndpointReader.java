package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Reads a webhook endpoint's subscription from Stripe ({@code GET /v1/webhook_endpoints/{id}}). */
public interface StripeWebhookEndpointReader {

    /**
     * @throws StripeWebhookEndpointException when Stripe cannot be read
     */
    Subscription read(String endpointId);

    /**
     * @param status        {@code enabled} or {@code disabled}
     * @param enabledEvents what the endpoint subscribes to; {@code *} means every event
     */
    record Subscription(String status, List<String> enabledEvents) {

        private static final String ALL_EVENTS = "*";
        private static final String ENABLED = "enabled";

        public Subscription {
            enabledEvents = enabledEvents == null ? List.of() : List.copyOf(enabledEvents);
        }

        /** The handled events Stripe will not deliver: all of them when the endpoint is disabled. */
        public SortedSet<String> missing(Set<String> handled) {
            SortedSet<String> missing = new TreeSet<>(handled);
            if (!ENABLED.equals(status)) {
                return missing;
            }
            if (enabledEvents.contains(ALL_EVENTS)) {
                missing.clear();
            } else {
                missing.removeAll(enabledEvents);
            }
            return missing;
        }
    }
}
