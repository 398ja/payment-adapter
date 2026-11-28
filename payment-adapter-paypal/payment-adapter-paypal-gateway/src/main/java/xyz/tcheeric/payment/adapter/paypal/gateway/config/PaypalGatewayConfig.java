package xyz.tcheeric.payment.adapter.paypal.gateway.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalCheckoutService;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalHttp;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalRestOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalRestSubscriptionsClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;

/** PayPal on, or nothing at all: the counterpart of {@code StripeGatewayConfig}. */
@Configuration
@EnableConfigurationProperties(PaypalGatewayProperties.class)
@ConditionalOnProperty(prefix = "paypal", name = "enabled", havingValue = "true")
public class PaypalGatewayConfig {

    @Bean
    @ConditionalOnMissingBean
    PaypalHttp paypalHttp(PaypalGatewayProperties properties) {
        return new PaypalHttp(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    PaypalOrdersClient paypalOrdersClient(PaypalHttp paypalHttp) {
        return new PaypalRestOrdersClient(paypalHttp);
    }

    @Bean
    @ConditionalOnMissingBean
    PaypalSubscriptionsClient paypalSubscriptionsClient(PaypalHttp paypalHttp) {
        return new PaypalRestSubscriptionsClient(paypalHttp);
    }

    @Bean
    PaypalCheckoutService paypalCheckoutService(PaypalGatewayProperties properties, PaypalOrdersClient orders) {
        return new PaypalCheckoutService(properties, orders);
    }

    /** The payments webhook's verifier, for {@code paypal.webhook-id}. */
    @Bean
    @ConditionalOnMissingBean(name = "paypalWebhookVerifier")
    PaypalWebhookVerifier paypalWebhookVerifier(PaypalGatewayProperties properties) {
        return new PaypalWebhookVerifier(properties.getWebhookId(), properties::isTrustedCertHost,
                Duration.ofSeconds(properties.getWebhookToleranceSeconds()));
    }
}
