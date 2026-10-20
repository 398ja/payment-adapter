package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Checks that every Stripe webhook endpoint subscribes to every event type its handler acts on
 * (payment-adapter#286 item 2).
 *
 * <p>Stripe does not warn about a missing subscription, and the handler cannot see an event it was
 * never sent: on staging the payments endpoint lacked {@code charge.refunded} and
 * {@code charge.dispute.created}, so every refund and dispute was silently lost. Checked once the
 * application is ready and again on the reconcile schedule.
 *
 * <p>Two gauges per endpoint, both bound at zero so an alert sees a healthy zero, not no series:
 * <ul>
 *   <li>{@value #MISSING}: handled events the endpoint does not deliver (all of them when it is
 *       disabled). Held at its last value while Stripe cannot be read.</li>
 *   <li>{@value #CHECK_FAILED}: 1 when the last check could not read the endpoint (Stripe error, or
 *       no endpoint id configured), else 0.</li>
 * </ul>
 *
 * <p>Never throws and never blocks startup on Stripe: a broken check is an alert, not an outage. Logs
 * endpoint ids and event names only, never a key or signing secret.
 */
@Slf4j
public class WebhookSubscriptionCheck {

    /** Handled events the endpoint is not subscribed to. */
    public static final String MISSING = "imani_stripe_webhook_events_missing";
    /** 1 when the endpoint's subscription could not be checked. */
    public static final String CHECK_FAILED = "imani_stripe_webhook_subscription_check_failed";

    private final List<StripeWebhookEndpoint> endpoints;
    private final StripeWebhookEndpointReader stripe;
    private final Map<String, AtomicLong> missing = new LinkedHashMap<>();
    private final Map<String, AtomicLong> failed = new LinkedHashMap<>();

    public WebhookSubscriptionCheck(List<StripeWebhookEndpoint> endpoints, StripeWebhookEndpointReader stripe,
            MeterRegistry registry) {
        this.endpoints = List.copyOf(endpoints);
        this.stripe = stripe;
        for (StripeWebhookEndpoint endpoint : this.endpoints) {
            missing.put(endpoint.name(), gauge(registry, MISSING, endpoint,
                    "Event types the handler acts on that this Stripe webhook endpoint does not deliver"));
            failed.put(endpoint.name(), gauge(registry, CHECK_FAILED, endpoint,
                    "1 when this Stripe webhook endpoint's subscription could not be checked"));
        }
    }

    private static AtomicLong gauge(MeterRegistry registry, String name, StripeWebhookEndpoint endpoint,
            String description) {
        AtomicLong value = new AtomicLong();
        if (registry != null) {
            Gauge.builder(name, value, AtomicLong::get)
                    .description(description)
                    .tag("endpoint", endpoint.name())
                    .register(registry);
        }
        return value;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        checkAll();
    }

    @Scheduled(fixedDelayString = "${purchase.reconcile.interval:PT10M}",
            initialDelayString = "${purchase.reconcile.interval:PT10M}")
    public void onSchedule() {
        checkAll();
    }

    /** Checks every endpoint. Visible for tests. */
    public void checkAll() {
        for (StripeWebhookEndpoint endpoint : endpoints) {
            try {
                check(endpoint);
            } catch (RuntimeException e) {
                checkFailed(endpoint, "check failed unexpectedly: " + e.getClass().getSimpleName());
            }
        }
    }

    private void check(StripeWebhookEndpoint endpoint) {
        if (!endpoint.isConfigured()) {
            checkFailed(endpoint, "no endpoint id configured, so its enabled_events cannot be checked");
            return;
        }
        StripeWebhookEndpointReader.Subscription subscription;
        try {
            subscription = stripe.read(endpoint.endpointId());
        } catch (StripeWebhookEndpointException e) {
            checkFailed(endpoint, e.getMessage());
            return;
        }
        failed.get(endpoint.name()).set(0);
        SortedSet<String> notDelivered = subscription.missing(endpoint.handledEvents());
        missing.get(endpoint.name()).set(notDelivered.size());
        if (notDelivered.isEmpty()) {
            log.info("Stripe webhook endpoint {} ({}) delivers all {} handled event types",
                    endpoint.name(), endpoint.endpointId(), endpoint.handledEvents().size());
        } else {
            log.warn("[alert] Stripe webhook endpoint {} ({}, status {}) does not deliver {}: those events are"
                            + " lost. Add them to its enabled_events",
                    endpoint.name(), endpoint.endpointId(), subscription.status(), notDelivered);
        }
    }

    private void checkFailed(StripeWebhookEndpoint endpoint, String why) {
        failed.get(endpoint.name()).set(1);
        log.warn("[alert] Stripe webhook endpoint {} subscription not checked: {}", endpoint.name(), why);
    }
}
