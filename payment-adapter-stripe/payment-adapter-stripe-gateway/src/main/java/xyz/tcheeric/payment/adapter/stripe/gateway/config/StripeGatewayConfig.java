package xyz.tcheeric.payment.adapter.stripe.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.common.GatewayRegistry;
import xyz.tcheeric.payment.adapter.stripe.gateway.StripeGateway;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeSdkCheckoutClient;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(StripeGatewayProperties.class)
@ConditionalOnProperty(prefix = "stripe", name = "enabled", havingValue = "true")
public class StripeGatewayConfig {

    private final StripeGatewayProperties properties;

    @Bean
    QuoteClient quoteClient() {
        return new QuoteClient();
    }

    @Bean
    PaymentClient paymentClient() {
        return new PaymentClient();
    }

    @Bean
    StripeCheckoutClient stripeCheckoutClient() {
        return new StripeSdkCheckoutClient(properties);
    }

    @Bean
    StripeCheckoutService stripeCheckoutService(StripeCheckoutClient stripeCheckoutClient) {
        return new StripeCheckoutService(properties, stripeCheckoutClient);
    }

    @Bean
    InitializingBean stripeGatewayRegistrar(StripeGateway stripeGateway,
                                            org.springframework.beans.factory.ObjectProvider<GatewayRegistry> registryProvider) {
        return () -> registryProvider.ifAvailable(registry -> registry.register(stripeGateway));
    }
}
