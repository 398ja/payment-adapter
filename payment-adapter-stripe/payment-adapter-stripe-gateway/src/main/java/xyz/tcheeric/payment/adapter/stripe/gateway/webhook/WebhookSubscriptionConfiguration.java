package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * Wires {@link WebhookSubscriptionCheck} (#286) over every {@link StripeWebhookEndpoint} the handler
 * modules contribute. Only with Stripe on: with it off there is no key to read with.
 */
@Configuration
@ConditionalOnProperty(prefix = "stripe", name = "enabled", havingValue = "true")
public class WebhookSubscriptionConfiguration {

    @Bean
    StripeWebhookEndpointReader stripeWebhookEndpointReader(StripeGatewayProperties properties) {
        return new StripeSdkWebhookEndpointReader(properties);
    }

    @Bean
    WebhookSubscriptionCheck webhookSubscriptionCheck(ObjectProvider<StripeWebhookEndpoint> endpoints,
            StripeWebhookEndpointReader reader, ObjectProvider<MeterRegistry> registry) {
        return new WebhookSubscriptionCheck(endpoints.orderedStream().toList(), reader, registry.getIfAvailable());
    }
}
