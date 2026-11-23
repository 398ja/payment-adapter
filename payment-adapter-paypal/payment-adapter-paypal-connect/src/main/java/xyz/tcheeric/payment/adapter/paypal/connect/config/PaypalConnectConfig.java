package xyz.tcheeric.payment.adapter.paypal.connect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalConnectService;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalPartnerClient;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalPaymentReferenceVerifier;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalRestPartnerClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalHttp;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;

/**
 * Seller onboarding, on only with {@code paypal.connect.enabled}. Unlike Stripe's Connect config the
 * beans are absent when it is off, since nothing else asks for them; the startup refusal is the same.
 */
@Configuration
@EnableConfigurationProperties({PaypalConnectProperties.class, PaypalGatewayProperties.class})
@ConditionalOnProperty(prefix = "paypal.connect", name = "enabled", havingValue = "true")
public class PaypalConnectConfig {

    /** Checked first, before a missing PayPal bean could fail startup with a less useful message. */
    public PaypalConnectConfig(PaypalGatewayProperties gateway) {
        requireGateway(gateway);
    }

    /**
     * Connect without the gateway is a stall shown as taking PayPal with no checkout to take it:
     * {@code StripeConnectConfig#requireGatewayWhenConnectIsEnabled}'s refusal, for PayPal.
     */
    static void requireGateway(PaypalGatewayProperties gateway) {
        if (!gateway.isEnabled()) {
            throw new IllegalStateException(
                    "paypal.connect.enabled=true requires paypal.enabled=true. Onboarding alone registers no "
                            + "checkout endpoint, so every PayPal purchase would answer 404 after the stall has "
                            + "already advertised that it takes cards. Set PAYPAL_ENABLED=true, or turn PayPal "
                            + "Connect off.");
        }
    }

    @Bean
    PaypalPartnerClient paypalPartnerClient(PaypalHttp http, PaypalConnectProperties properties) {
        return new PaypalRestPartnerClient(http, properties);
    }

    /** Its own verifier: the onboarding webhook has its own PayPal webhook id. */
    @Bean
    PaypalWebhookVerifier paypalConnectWebhookVerifier(PaypalGatewayProperties gateway,
            PaypalConnectProperties properties) {
        return new PaypalWebhookVerifier(properties.getWebhookId(), gateway::isTrustedCertHost,
                Duration.ofSeconds(gateway.getWebhookToleranceSeconds()));
    }

    @Bean
    PaypalConnectService paypalConnectService(PaypalConnectProperties properties, PaypalGatewayProperties gateway,
            PaypalPartnerClient partner,
            @Qualifier("paypalConnectWebhookVerifier") PaypalWebhookVerifier verifier,
            ObjectMapper objectMapper, ConnectedPaypalAccountRepository accounts,
            ProcessedPaypalWebhookEventRepository events, PlatformTransactionManager transactionManager) {
        return new PaypalConnectService(properties, gateway, partner, verifier, objectMapper, accounts, events,
                transactionManager);
    }

    @Bean
    PaypalPaymentReferenceVerifier paypalPaymentReferenceVerifier(ConnectedPaypalAccountRepository accounts,
            StripePurchaseRepository purchases, PaypalOrdersClient orders, PaypalGatewayProperties gateway) {
        return new PaypalPaymentReferenceVerifier(accounts, purchases, orders, gateway.isLive());
    }
}
