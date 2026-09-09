package xyz.tcheeric.payment.adapter.stripe.gateway.config;

import com.stripe.net.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "stripe")
public class StripeGatewayProperties {

    /**
     * Hosts an api-base override may name.
     *
     * <p>Loopback, plus the two names a container uses to mean "a fake running
     * beside me": Docker's host alias and the conventional service name for
     * stripe-mock. Everything else is refused.
     *
     * <p>Allowing {@code stripe-mock} is a deliberate narrowing, not a
     * loosening. The alternative considered first was loopback-only, which
     * sounds stricter and is unworkable: a container reaching the host would
     * need {@code extra_hosts: "localhost:host-gateway"}, and remapping
     * localhost inside the container breaks its own health check — which probes
     * {@code http://localhost:8080}. A guard that forces a footgun elsewhere is
     * not a strict guard, it is a relocated one.
     *
     * <p>An exact allow-list keeps the property that matters: no arbitrary
     * hostname is accepted, so {@code stripe-api.internal.example.com} is still
     * refused. A production deployment would have to name one of these
     * specifically, and none of them resolves to anything there.
     */
    private static final Set<String> ALLOWED_API_BASE_HOSTS = Set.of(
            "localhost", "127.0.0.1", "[::1]", "::1", "host.docker.internal", "stripe-mock");

    private boolean enabled;
    private String secretKey;
    private String successUrl;
    private String cancelUrl;
    private String defaultCurrency = "usd";
    private List<String> allowedCurrencies = new ArrayList<>(List.of("usd"));

    /**
     * Where the Stripe API lives. Empty means Stripe itself, which is the only
     * value any real deployment should ever have.
     *
     * <p>Exists so a developer can run the card flows against
     * {@code stripe-mock}, Stripe's own fake API server, with no Stripe account
     * and no key that could ever move money. Without it, local work on Connect
     * onboarding is blocked behind registering a business — the flows simply
     * answered 503 and could not be exercised at all. The project spec already
     * expects this ("CI tests should use deterministic mocks such as WireMock or
     * {@code stripe-mock}"); nothing had wired it up.
     *
     * <p><b>This is the most dangerous property in the file, and it is dangerous
     * in the quiet direction.</b> A wrong secret key fails loudly and instantly.
     * A wrong api-base makes every charge, refund and payout SUCCEED against
     * something that is not Stripe: the system reports healthy, the books look
     * right, and no money has moved. It would be discovered by a merchant asking
     * where their payout is.
     *
     * <p>So {@link #validate()} refuses to start unless it names a known local
     * mock, rather than trusting deployments to leave it unset. See there for why
     * the check is on the HOST and not on a "looks like a test key" heuristic.
     */
    private String apiBase;

    @Min(60)
    private int checkoutExpirySeconds = 1800;

    @Min(1)
    private int webhookToleranceSeconds = 300;

    @Min(1)
    private int minAmountMinor = 1;

    @Min(1)
    private int maxAmountMinor = Integer.MAX_VALUE;

    @PostConstruct
    void validate() {
        defaultCurrency = normalizeCurrency(defaultCurrency);
        allowedCurrencies = allowedCurrencies.stream()
                .map(this::normalizeCurrency)
                .distinct()
                .toList();

        if (!allowedCurrencies.contains(defaultCurrency)) {
            allowedCurrencies = new ArrayList<>(allowedCurrencies);
            allowedCurrencies.add(defaultCurrency);
        }

        if (!enabled) {
            return;
        }

        requireValue(secretKey, "stripe.secret-key");
        requireValue(successUrl, "stripe.success-url");
        requireValue(cancelUrl, "stripe.cancel-url");
        requireLocalApiBase();
    }

    /** True when this instance talks to something other than Stripe. */
    public boolean isApiBaseOverridden() {
        return apiBase != null && !apiBase.isBlank();
    }

    /**
     * Starts a RequestOptions builder already pointed at the right API.
     *
     * <p>Every Stripe call in this project must go through here. There are three
     * RequestOptions builders across two clients — create-checkout,
     * retrieve-checkout and Connect — and each one that forgot the api-base
     * would silently talk to REAL Stripe while its neighbours talked to the
     * mock. That is a worse state than not having the feature: a developer
     * running against a fake would have some calls hit production with whatever
     * key was configured.
     *
     * <p>So the conditional lives in one place and the callers cannot skip it,
     * rather than being copied three times and drifting. Callers add their own
     * concerns — the api key, idempotency, the connected account — on top.
     */
    public RequestOptions.RequestOptionsBuilder requestOptions() {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder();
        if (isApiBaseOverridden()) {
            builder.setBaseUrl(apiBase.trim());
        }
        return builder;
    }

    /**
     * Refuses to start when {@code stripe.api-base} points anywhere but this
     * machine.
     *
     * <p>An override is a development convenience with a production failure mode
     * that has no natural alarm: pointed at a fake, every charge, refund and
     * payout SUCCEEDS. Nothing errors, health stays green, the database fills
     * with plausible records, and no money moves. The first symptom is a
     * merchant asking where their payout is, weeks later. That is a bad enough
     * outcome to be worth refusing a boot over — an unstartable service is
     * noticed in minutes.
     *
     * <p><b>The check is on the HOST, deliberately, not on the key.</b> The
     * tempting alternative is "allow an override only with an {@code sk_test_}
     * key", and it fails in exactly the case that matters: the disaster is a LIVE
     * key sent to a fake, and a rule keyed on the test prefix never fires on a
     * live one. Loopback is the property we actually want — a real deployment
     * cannot reach Stripe over localhost, and a developer's mock is never
     * anywhere else.
     *
     * <p>The allowed set is an exact list, not a pattern: loopback plus the two
     * names a container uses for a mock beside it. Arbitrary hostnames stay
     * refused, so {@code stripe-api.internal.example.com} cannot slip through.
     * See {@link #ALLOWED_API_BASE_HOSTS} for why the docker service name is on
     * it, and imani-deploy/docker-compose.test.yml for the wiring.
     */
    private void requireLocalApiBase() {
        if (!isApiBaseOverridden()) {
            return;
        }

        String host;
        try {
            host = URI.create(apiBase.trim()).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "stripe.api-base is not a valid URL: " + apiBase, e);
        }

        if (host == null || !ALLOWED_API_BASE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            // Says what is set, what is allowed, and what the consequence would
            // have been. A refusal a reader cannot act on just gets deleted.
            throw new IllegalStateException(
                    "stripe.api-base must name a local mock (got: " + apiBase + "). "
                            + "Allowed hosts: " + ALLOWED_API_BASE_HOSTS + ". "
                            + "It exists ONLY for running against stripe-mock in development. "
                            + "Any other host would send real charges, refunds and payouts to "
                            + "something that is not Stripe, and every one of them would appear "
                            + "to succeed. Unset it to use the real Stripe API.");
        }
    }

    public boolean isCurrencyAllowed(String currency) {
        return allowedCurrencies.contains(normalizeCurrency(currency));
    }

    public String normalizeCurrency(String currency) {
        return currency == null ? null : currency.trim().toLowerCase(Locale.ROOT);
    }

    private void requireValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required Stripe property: " + propertyName);
        }
    }
}
