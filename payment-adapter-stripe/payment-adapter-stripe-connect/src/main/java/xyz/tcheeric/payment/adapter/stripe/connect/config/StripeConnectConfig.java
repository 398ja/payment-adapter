package xyz.tcheeric.payment.adapter.stripe.connect.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectClient;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeSdkConnectClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

@Configuration
@EnableConfigurationProperties({StripeConnectProperties.class, StripeGatewayProperties.class})
@RequiredArgsConstructor
public class StripeConnectConfig {

    private final StripeConnectProperties connectProperties;
    private final StripeGatewayProperties gatewayProperties;

    @Bean
    StripeConnectClient stripeConnectClient(StripeGatewayProperties gatewayProperties) {
        return new StripeSdkConnectClient(gatewayProperties);
    }

    /**
     * Connect without the gateway is a stall that can be paid and cannot take
     * money. Refuse to start rather than ship it.
     *
     * <p>{@code stripe.connect.enabled} turns on connected accounts.
     * {@code stripe.enabled} turns on the gateway beans — including
     * {@code StripeCheckoutService} and {@code PurchaseCheckoutController},
     * which is {@code @ConditionalOnProperty} on it. Setting only the first is
     * a coherent-looking configuration that fails in the worst possible place:
     *
     * <ul>
     *   <li>the stall connects Stripe and sees "connected";</li>
     *   <li>it publishes {@code acceptsCards}, so shoppers are shown a Buy
     *       button;</li>
     *   <li>and {@code POST /api/v1/checkout/purchase} answers <b>404</b>, from a
     *       controller that was never registered.</li>
     * </ul>
     *
     * <p>Nothing logs a reason, because nothing failed — the bean simply does
     * not exist. The 404 reaches the shopper as "Not Found" under an amount they
     * have already typed, and the stall has no way to know. It cost an evening
     * to find, from the wrong end.
     *
     * <p>Loud at startup instead. An unstartable service is noticed in minutes
     * by the person who can fix it; a half-configured one is noticed by a
     * customer.
     *
     * <p>The reverse — gateway on, Connect off — is NOT an error: it is a
     * platform taking payments for itself, which is a legitimate configuration
     * this adapter supports.
     */
    @PostConstruct
    void requireGatewayWhenConnectIsEnabled() {
        if (connectProperties.isEnabled() && !gatewayProperties.isEnabled()) {
            throw new IllegalStateException(
                    "stripe.connect.enabled=true requires stripe.enabled=true. "
                            + "Connect alone registers no checkout endpoint, so every "
                            + "purchase would answer 404 after the stall has already "
                            + "advertised that it takes cards. Set STRIPE_ENABLED=true, "
                            + "or turn Connect off.");
        }
    }
}
