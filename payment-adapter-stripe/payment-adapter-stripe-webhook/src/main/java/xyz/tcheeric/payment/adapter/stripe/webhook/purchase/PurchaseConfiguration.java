package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * The operator's token for {@code /resolve} (review of #280). Must differ from the issuer's;
     * unset (or equal) refuses every resolve, and nothing else changes.
     */
    @Value("${purchase.operator.token:}")
    private String operatorToken;

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
     * The public keys this service registered under before {@link #gatewayPrivateKey}, during a
     * rollover (#288): comma-separated x-only hex. PUBLIC keys only. Empty outside a rollover.
     */
    @Value("${purchase.gateway.previous-pubkeys:}")
    private String gatewayPreviousPubkeys;

    /**
     * When {@link #gatewayPreviousPubkeys} stop counting, ISO-8601. Required with any previous key,
     * at most {@link PreviousGatewayKeys#MAX_WINDOW} ahead: a leaked old key never keeps authority.
     */
    @Value("${purchase.gateway.previous-pubkeys-until:}")
    private String gatewayPreviousPubkeysUntil;

    /**
     * How long to wait on the gateway before treating the answer as unknown.
     *
     * <p>Short on purpose. A slow verification blocks a discharge, and an
     * unknown answer is safe — the debt simply stays owed and is retried.
     */
    @Value("${purchase.gateway.timeout-seconds:5}")
    private int gatewayTimeoutSeconds;

    /**
     * How long an issuer's claim on a purchase holds (imani-wallet#96).
     *
     * <p>Minutes, not seconds: a lapsed claim lets another worker mint again
     * under the same idempotency key, and that is only as safe as the
     * gateway's key store. Long enough that only a dead worker lets it lapse.
     */
    @Value("${purchase.claim.lease-seconds:600}")
    private long claimLeaseSeconds;

    /** Lapsed leases before a row is parked as stale_issuing (#273). */
    @Value("${purchase.claim.max-lease-expiries:3}")
    private int maxLeaseExpiries;

    /** purchase-issuer's base URL on the compose network, as staging names it (#293). */
    static final String DEFAULT_ISSUER_BASE_URL = "http://purchase-issuer:8791";

    /** How long to wait on purchase-issuer's readiness answer before refusing the checkout (#293). */
    @Value("${purchase.issuer.timeout-ms:2000}")
    private long issuerTimeoutMs;

    /** How long a stall's ready / not-ready answer is reused (#293). UNKNOWN is never reused. */
    @Value("${purchase.issuer.ready-cache-seconds:30}")
    private long issuerReadyCacheSeconds;

    /**
     * Readiness of a stall for card sales, asked of purchase-issuer before any Checkout Session
     * (payment-adapter#293).
     *
     * <p>{@code purchase.issuer.base-url} unset defaults to {@link #DEFAULT_ISSUER_BASE_URL} (the
     * staging compose name), EXCEPT under a {@code prod}/{@code production} profile, where an unset
     * URL stays empty and every card checkout answers 503: a production deployment must name its
     * issuer deliberately, and refusing is safer than guessing.
     */
    @Bean
    public StallReadinessClient stallReadinessClient(org.springframework.core.env.Environment env) {
        return new StallReadinessClient(issuerBaseUrl(env), Duration.ofMillis(issuerTimeoutMs),
                Duration.ofSeconds(issuerReadyCacheSeconds), java.time.Clock.systemUTC());
    }

    static String issuerBaseUrl(org.springframework.core.env.Environment env) {
        String configured = env.getProperty("purchase.issuer.base-url");
        if (configured != null) {
            return configured.trim();
        }
        boolean prod = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod | production"));
        return prod ? "" : DEFAULT_ISSUER_BASE_URL;
    }

    @Bean
    public GatewayFulfilmentClient gatewayFulfilmentClient() {
        return new GatewayFulfilmentClient(
                gatewayBaseUrl, gatewayPrivateKey, Duration.ofSeconds(gatewayTimeoutSeconds));
    }

    /** Parsed once at boot: a malformed or unbounded rollover stops the service rather than guessing. */
    PreviousGatewayKeys previousGatewayKeys() {
        return PreviousGatewayKeys.parse(gatewayPreviousPubkeys, gatewayPreviousPubkeysUntil, gatewayPrivateKey,
                java.time.Clock.systemUTC());
    }

    @Bean
    public PurchaseDischargeService purchaseDischargeService(
            StripePurchaseRepository purchases, GatewayFulfilmentClient fulfilment,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new PurchaseDischargeService(purchases, fulfilment, Duration.ofSeconds(claimLeaseSeconds),
                maxLeaseExpiries)
                .withPreviousKeys(previousGatewayKeys())
                .withMetrics(new xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics(registry.getIfAvailable()));
    }

    /**
     * {@code /resolve}, with the live Stripe read in front of a retry (#286). With Stripe off there is
     * no {@link LivePaymentCheck} bean, and every retry of a parked row fails closed (503).
     */
    @Bean
    public OperatorResolution operatorResolution(StripePurchaseRepository purchases,
            PurchaseDischargeService discharges, ObjectProvider<LivePaymentCheck> livePayments) {
        return new OperatorResolution(purchases, discharges,
                livePayments.getIfAvailable(LivePaymentCheck::unconfigured));
    }

    /** imani-deploy#210: paid card purchases with no coupon confirmed yet. */
    @Bean
    public PaidUnissuedGauge paidUnissuedGauge(
            StripePurchaseRepository purchases,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new PaidUnissuedGauge(purchases, registry.getIfAvailable());
    }

    /** #273: card purchases waiting for a person, by reason. */
    @Bean
    public NeedsAttentionGauge needsAttentionGauge(
            StripePurchaseRepository purchases,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new NeedsAttentionGauge(purchases, registry.getIfAvailable());
    }

    /** Re-asks about ISSUED rows whose discharge could not be verified (PA-2). */
    @Bean
    public IssuedPurchaseRecheck issuedPurchaseRecheck(
            PurchaseDischargeService discharges, GatewayFulfilmentClient fulfilment) {
        return new IssuedPurchaseRecheck(discharges, fulfilment);
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
            StripePurchaseRepository purchases, ConnectedStripeAccountRepository accounts,
            GatewayFulfilmentClient fulfilment) {
        return new RecordingPurchaseListener(purchases, accounts, fulfilment);
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
        registration.setFilter(new PurchaseApiTokenFilter(apiToken, operatorToken));
        /*
         * Registered on EVERYTHING, and the filter decides.
         *
         * Registering on `/api/v1/purchases/*` looked tighter and was weaker:
         * the container matches that pattern against the decoded servlet path,
         * so a request the container routes to the controller by a path the
         * pattern does not match never reaches the filter at all. A filter that
         * is not invoked cannot refuse anything.
         *
         * Matching every request and returning early for the rest costs one
         * string comparison and removes a whole class of bypass.
         */
        registration.addUrlPatterns("/*");
        return registration;
    }
}
