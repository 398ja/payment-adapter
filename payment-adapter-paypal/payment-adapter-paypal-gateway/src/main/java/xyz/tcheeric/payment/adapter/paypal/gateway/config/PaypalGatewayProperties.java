package xyz.tcheeric.payment.adapter.paypal.gateway.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * PayPal, for card purchases on a stall's own PayPal account (ADR 0002). The mirror of
 * {@code StripeGatewayProperties}: the same switches, the same refusals at startup.
 *
 * <p>One platform REST app ({@code client-id}/{@code client-secret}) acts for every onboarded
 * seller through {@code PayPal-Auth-Assertion}, as one Stripe platform key acts for every connected
 * account through {@code Stripe-Account}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "paypal")
public class PaypalGatewayProperties {

    /** Same allow-list, and the same reasons, as {@code StripeGatewayProperties#ALLOWED_API_BASE_HOSTS}. */
    private static final Set<String> ALLOWED_API_BASE_HOSTS = Set.of(
            "localhost", "127.0.0.1", "[::1]", "::1", "host.docker.internal", "paypal-mock");

    static final String LIVE_API = "https://api-m.paypal.com";
    static final String SANDBOX_API = "https://api-m.sandbox.paypal.com";

    private boolean enabled;

    /** {@code sandbox} (default) or {@code live}. Decides the API host and every row's {@code livemode}. */
    private String mode = "sandbox";

    private String clientId;

    // Never in toString: a logged or exception-rendered properties bean must not carry the secret.
    @ToString.Exclude
    private String clientSecret;

    /**
     * The platform's PayPal Partner Attribution (BN) code, sent as
     * {@code PayPal-Partner-Attribution-Id} on every call. Blank: not sent.
     */
    private String bnCode;

    /**
     * Where a buyer ends up after paying, as {@code stripe.purchase-success-url} is for Stripe. May
     * carry {@code {ORDER_ID}}, the counterpart of Stripe's {@code {CHECKOUT_SESSION_ID}}.
     */
    private String successUrl;

    /** Where a buyer goes after cancelling on PayPal's page. */
    private String cancelUrl;

    /**
     * This service's public origin (e.g. {@code https://pay.imani.casa}). When set, PayPal returns the
     * buyer to {@code /api/v1/checkout/paypal/return} here, which captures the payment at once and
     * then sends them on to {@link #successUrl}. Blank: PayPal returns them straight to the success
     * URL and the capture happens on {@code CHECKOUT.ORDER.APPROVED} or by the reconciler.
     */
    private String returnBaseUrl;

    /** The payments webhook's PayPal id ({@code PAYPAL_WEBHOOK_ID}), part of every signed message. */
    private String webhookId;

    /** Shown on PayPal's page as the seller's brand, or blank for the seller's own name. */
    private String brandName;

    /**
     * Which deployment this is, stamped on every order's {@code custom_id} as {@code <environment>:<quote>}
     * (ADR 0002), the counterpart of the {@code imani_env} metadata on a Checkout Session. It MUST be
     * the same name the purchase ledger knows this deployment by ({@code stripe.environment}): the
     * ledger reads an order naming any other environment as foreign and records nothing. The rest app
     * defaults it to {@code STRIPE_ENVIRONMENT}, and the webhook module refuses to start when the two
     * disagree. Blank: orders carry the quote alone, and every paid order is recorded parked
     * ({@code unstamped_session}) only if the ledger itself has a name.
     */
    private String environment;

    /** {@link #environment}, trimmed and lower-cased, or null when unset. */
    public String resolvedEnvironment() {
        return environment == null || environment.isBlank() ? null
                : environment.trim().toLowerCase(Locale.ROOT);
    }

    /** Same meaning as Stripe's, and the same exposure: see {@code StripeGatewayProperties#apiBase}. */
    private String apiBase;

    private String defaultCurrency = "usd";
    private List<String> allowedCurrencies = new ArrayList<>(List.of("usd"));

    @Min(1)
    private int minAmountMinor = 1;

    @Min(1)
    private int maxAmountMinor = Integer.MAX_VALUE;

    /** Bounds on every PayPal call; a buyer waits on the order create (payment-adapter#274). */
    @Min(1)
    private int connectTimeoutMs = 5_000;

    @Min(1)
    private int readTimeoutMs = 10_000;

    /** Retries of a call PayPal could not answer. Safe: every write carries a {@code PayPal-Request-Id}. */
    @Min(0)
    private int maxNetworkRetries = 1;

    /**
     * How old a webhook's {@code PAYPAL-TRANSMISSION-TIME} may be, in seconds, or 0 for no bound.
     * Off by default: PayPal documents no tolerance, and a redelivery refused for its age would be a
     * paid purchase never recorded. The event-id table already makes a replay a duplicate.
     */
    @Min(0)
    private int webhookToleranceSeconds = 0;

    @PostConstruct
    void validate() {
        defaultCurrency = normalizeCurrency(defaultCurrency);
        allowedCurrencies = allowedCurrencies.stream().map(this::normalizeCurrency).distinct().toList();
        if (!allowedCurrencies.contains(defaultCurrency)) {
            allowedCurrencies = new ArrayList<>(allowedCurrencies);
            allowedCurrencies.add(defaultCurrency);
        }
        mode = mode == null ? "sandbox" : mode.trim().toLowerCase(Locale.ROOT);

        if (!enabled) {
            return;
        }
        if (!"sandbox".equals(mode) && !"live".equals(mode)) {
            throw new IllegalStateException("paypal.mode must be 'sandbox' or 'live' (got: " + mode + ")");
        }
        requireValue(clientId, "paypal.client-id");
        requireValue(clientSecret, "paypal.client-secret");
        requireValue(successUrl, "paypal.success-url");
        requireValue(cancelUrl, "paypal.cancel-url");
        requireLocalApiBase();
    }

    public boolean isLive() {
        return "live".equals(mode);
    }

    public boolean isApiBaseOverridden() {
        return apiBase != null && !apiBase.isBlank();
    }

    /** The API origin every call goes to, without a trailing slash. */
    public String resolvedApiBase() {
        String base = isApiBaseOverridden() ? apiBase.trim() : isLive() ? LIVE_API : SANDBOX_API;
        return base.replaceAll("/+$", "");
    }

    /**
     * The URL PayPal sends an approving buyer back to: this service's capture route, or the success URL.
     * Straight to the success URL, {@code {ORDER_ID}} is dropped: the order does not exist yet when
     * the URL is sent, and PayPal appends {@code token=<order id>} to it anyway.
     */
    public String resolvedReturnUrl(String orderPlaceholderSuccessUrl) {
        if (returnBaseUrl == null || returnBaseUrl.isBlank()) {
            return orderPlaceholderSuccessUrl == null ? null
                    : orderPlaceholderSuccessUrl.replace(ORDER_ID_PLACEHOLDER, "");
        }
        return returnBaseUrl.trim().replaceAll("/+$", "") + RETURN_PATH;
    }

    /** This service's capture-on-return route (ADR 0002). */
    public static final String RETURN_PATH = "/api/v1/checkout/paypal/return";

    /** The placeholder in {@link #successUrl} replaced by the order id. */
    public static final String ORDER_ID_PLACEHOLDER = "{ORDER_ID}";

    /**
     * Refuses to start when {@code paypal.api-base} names anything but a local mock: pointed at a fake,
     * every capture and refund appears to succeed and no money moves. Same rule, on the host, as
     * {@code StripeGatewayProperties#requireLocalApiBase}.
     */
    private void requireLocalApiBase() {
        if (!isApiBaseOverridden()) {
            return;
        }
        String host;
        try {
            host = URI.create(apiBase.trim()).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("paypal.api-base is not a valid URL: " + apiBase, e);
        }
        if (host == null || !ALLOWED_API_BASE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "paypal.api-base must name a local mock (got: " + apiBase + "). "
                            + "Allowed hosts: " + ALLOWED_API_BASE_HOSTS + ". "
                            + "Any other host would send real captures and refunds to something that is "
                            + "not PayPal, and every one of them would appear to succeed. Unset it to use "
                            + "the real PayPal API.");
        }
    }

    /** Whether a webhook certificate may be fetched from this host: PayPal's, or the local mock's. */
    public boolean isTrustedCertHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("paypal.com") || h.endsWith(".paypal.com")) {
            return true;
        }
        return isApiBaseOverridden() && ALLOWED_API_BASE_HOSTS.contains(h);
    }

    public boolean isCurrencyAllowed(String currency) {
        return allowedCurrencies.contains(normalizeCurrency(currency));
    }

    public String normalizeCurrency(String currency) {
        return currency == null ? null : currency.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required PayPal property: " + propertyName);
        }
    }
}
