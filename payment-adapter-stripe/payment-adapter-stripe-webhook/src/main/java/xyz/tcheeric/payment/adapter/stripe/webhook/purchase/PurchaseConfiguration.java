package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * Wiring the purchase side into the application.
 *
 * <p>Everything here is optional configuration with a working default, except
 * the two secrets, which have none: a deployment that does not set them gets a
 * purchase API that refuses every request and a verifier that answers UNKNOWN.
 * Both refuse rather than allow, so forgetting to configure this is visible
 * immediately instead of at the first discharge.
 */
@Configuration
public class PurchaseConfiguration {

    /**
     * The bearer token the issuer presents.
     *
     * <p>No default. An empty token makes {@link PurchaseApiTokenFilter} refuse
     * everything, which is the correct behaviour for an unconfigured
     * deployment: loud, rather than open.
     */
    @Value("${purchase.api.token:}")
    private String apiToken;

    /** Where to ask whether a coupon was really issued. */
    @Value("${purchase.gateway.base-url:}")
    private String gatewayBaseUrl;

    /**
     * This service's own Nostr key, hex.
     *
     * <p>Only for signing NIP-98 on the fulfilment check. It issues nothing and
     * holds no stall's authority, so its loss lets an attacker ask the gateway
     * about payment request ids they already have.
     */
    @Value("${purchase.gateway.private-key:}")
    private String gatewayPrivateKey;

    /**
     * How long to wait on the gateway before treating the answer as unknown.
     *
     * <p>Short on purpose. A slow verification blocks a discharge, and an
     * unknown answer is safe — the debt simply stays owed and is retried.
     */
    @Value("${purchase.gateway.timeout-seconds:5}")
    private int gatewayTimeoutSeconds;

    @Bean
    public GatewayFulfilmentClient gatewayFulfilmentClient() {
        return new GatewayFulfilmentClient(
                gatewayBaseUrl, gatewayPrivateKey, Duration.ofSeconds(gatewayTimeoutSeconds));
    }

    @Bean
    public PurchaseDischargeService purchaseDischargeService(
            StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment) {
        return new PurchaseDischargeService(purchases, fulfilment);
    }

    /**
     * The listener the webhook hands paid purchases to.
     *
     * <p>Its presence is what turns a paid coupon purchase from an error into a
     * recorded debt: without one, {@code StripeWebhookHandler} refuses the
     * event rather than marking it processed.
     */
    @Bean
    public StripePurchaseListener stripePurchaseListener(
            StripePurchaseRepository purchases, ConnectedStripeAccountRepository accounts) {
        return new RecordingPurchaseListener(purchases, accounts);
    }

    /**
     * Registered against the purchase prefix ONLY.
     *
     * <p>Deliberately not a global filter. The webhook endpoints authenticate
     * themselves by Stripe signature and must keep working without a bearer
     * token, so widening this would break the thing that feeds the purchases.
     */
    @Bean
    public FilterRegistrationBean<PurchaseApiTokenFilter> purchaseApiTokenFilter() {
        FilterRegistrationBean<PurchaseApiTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PurchaseApiTokenFilter(apiToken));
        registration.addUrlPatterns(PurchaseApiTokenFilter.PROTECTED_PREFIX + "/*");
        return registration;
    }
}
