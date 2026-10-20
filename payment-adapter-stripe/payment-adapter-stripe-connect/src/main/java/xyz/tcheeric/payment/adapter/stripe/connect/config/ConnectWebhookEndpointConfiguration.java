package xyz.tcheeric.payment.adapter.stripe.connect.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectEvent;
import xyz.tcheeric.payment.adapter.stripe.gateway.webhook.StripeWebhookEndpoint;

/**
 * The Connect endpoint ({@code /api/v1/stripe/connect/webhooks}) and what it must deliver, for the
 * subscription check (#286). Only with Connect on: with it off nothing listens there.
 */
@Configuration
@ConditionalOnProperty(prefix = "stripe.connect", name = "enabled", havingValue = "true")
public class ConnectWebhookEndpointConfiguration {

    static final String NAME = "connect";

    @Bean
    StripeWebhookEndpoint connectWebhookEndpoint(StripeConnectProperties properties) {
        return new StripeWebhookEndpoint(NAME, properties.getWebhookEndpointId(), StripeConnectEvent.types());
    }
}
