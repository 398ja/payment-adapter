package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import java.util.Set;

/**
 * One Stripe webhook endpoint this service receives on, and every event type its handler acts on
 * (#286 item 2). Contributed by the module that owns the handler, so the list is derived from the
 * handler's own event registry rather than copied.
 *
 * @param name          a short label for logs and the metric's {@code endpoint} tag, e.g. {@code payments}
 * @param endpointId    Stripe's {@code we_...} id, or blank when the deployment did not name it
 * @param handledEvents event types the handler acts on, each of which the endpoint must subscribe to
 */
public record StripeWebhookEndpoint(String name, String endpointId, Set<String> handledEvents) {

    public StripeWebhookEndpoint {
        handledEvents = Set.copyOf(handledEvents);
    }

    public boolean isConfigured() {
        return endpointId != null && !endpointId.isBlank();
    }
}
