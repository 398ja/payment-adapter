package xyz.tcheeric.payment.adapter.stripe.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.webhook.StripeWebhookEndpoint;

/** The payments endpoint ({@code /webhook/stripe}) and what it must deliver, for the subscription check (#286). */
@Configuration
@ConditionalOnProperty(prefix = "stripe", name = "enabled", havingValue = "true")
public class PaymentsWebhookEndpointConfiguration {

    static final String NAME = "payments";

    @Bean
    StripeWebhookEndpoint paymentsWebhookEndpoint(StripeGatewayProperties properties) {
        return new StripeWebhookEndpoint(NAME, properties.getWebhookEndpointId(), StripePaymentEvent.types());
    }
}
