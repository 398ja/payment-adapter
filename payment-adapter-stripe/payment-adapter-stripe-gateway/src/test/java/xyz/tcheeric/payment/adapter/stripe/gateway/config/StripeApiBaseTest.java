package xyz.tcheeric.payment.adapter.stripe.gateway.config;

import com.stripe.net.RequestOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code stripe.api-base}: the property that lets a developer work without a
 * Stripe account, and the one that must never reach production.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Stripe Connect onboarding could not be exercised locally at all. Enabling
 * it needs a secret key, a key needs an account, and a Connect platform needs a
 * completed business profile — so the flows answered 503 CONNECT_DISABLED and
 * every card feature was unreachable on a developer's machine. Pointing the SDK
 * at {@code stripe-mock}, Stripe's own fake, removes that whole dependency.
 *
 * <h2>Why it is guarded so hard</h2>
 *
 * <p>An api-base is dangerous in the QUIET direction, which is what makes it
 * worth a boot failure. A wrong secret key fails immediately and loudly. A wrong
 * api-base makes every charge, refund and payout SUCCEED against something that
 * is not Stripe: no errors, health green, records written, and no money moved.
 * The first symptom would be a merchant asking where their payout is.
 *
 * <p>The guard is on the HOST rather than the key, and that choice is the
 * substance of these tests. "Only allow an override with an sk_test_ key" is the
 * obvious rule and it fails in exactly the case that matters — the disaster is a
 * LIVE key aimed at a fake, and a test-prefix rule never fires on a live key.
 */
class StripeApiBaseTest {

    /** A minimally valid enabled configuration; api-base is set per test. */
    private static StripeGatewayProperties enabled(String apiBase) {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setEnabled(true);
        properties.setSecretKey("sk_test_x");
        properties.setSuccessUrl("http://localhost/success");
        properties.setCancelUrl("http://localhost/cancel");
        properties.setApiBase(apiBase);
        return properties;
    }

    /* The everyday case: no override, so the SDK talks to Stripe. */
    @Test
    void unsetApiBaseLeavesTheSdkPointedAtStripe() {
        StripeGatewayProperties properties = enabled(null);
        properties.validate();

        assertThat(properties.isApiBaseOverridden()).isFalse();
        assertThat(properties.requestOptions().build().getBaseUrl())
                .as("no override must mean the SDK default, not an empty base URL")
                .isNull();
    }

    /* Blank is the same as unset — an empty env var must not count as an override. */
    @Test
    void blankApiBaseIsNotAnOverride() {
        StripeGatewayProperties properties = enabled("   ");
        properties.validate();

        assertThat(properties.isApiBaseOverridden()).isFalse();
        assertThat(properties.requestOptions().build().getBaseUrl()).isNull();
    }

    /* The point of the feature: a local mock is accepted and actually applied. */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:12111",
            "http://127.0.0.1:12111",
            "http://LOCALHOST:12111",
            // The compose default. A container cannot reach the host on
            // loopback without remapping localhost inside itself, which would
            // break its own health check — so the service name is allowed.
            "http://stripe-mock:12111",
            "http://host.docker.internal:12111",
    })
    void localMockOverridesAreAcceptedAndReachTheSdk(String apiBase) {
        StripeGatewayProperties properties = enabled(apiBase);

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.requestOptions().build().getBaseUrl()).isEqualTo(apiBase.trim());
    }

    /**
     * THE GUARD. Every one of these would send real money operations somewhere
     * that is not Stripe, and every one of them would look like it worked.
     *
     * <p>The allow-list is exact rather than a pattern, and this is what that
     * buys: an internal hostname that merely LOOKS like infrastructure is
     * refused. A rule like "any private address" or "anything not api.stripe.com"
     * would accept every one of these.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.stripe.com",
            "https://stripe-api.internal.example.com",
            "http://10.0.0.5:12111",
            // Close enough to the allowed name to be worth pinning: an exact
            // match is the property, not a substring.
            "http://stripe-mock.example.com:12111",
            "http://evil-stripe-mock:12111",
    })
    void nonLocalOverridesRefuseToStart(String apiBase) {
        StripeGatewayProperties properties = enabled(apiBase);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.api-base must name a local mock")
                // The message must carry the offending value and the way out.
                // A refusal a reader cannot act on is one they will delete.
                .hasMessageContaining(apiBase)
                .hasMessageContaining("Unset it");
    }

    /*
     * A live key aimed at a fake is the worst case, and it is refused for the
     * same reason as everything else here: the HOST is wrong. Written out
     * because a future reader may be tempted to relax the rule for test keys,
     * and this is the case that rule would miss.
     */
    @Test
    void aLiveKeyPointedAtARemoteFakeIsStillRefused() {
        StripeGatewayProperties properties = enabled("https://stripe-api.internal.example.com");
        properties.setSecretKey("sk_live_realmoney");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must name a local mock");
    }

    /* Nonsense must be refused rather than silently ignored. */
    @Test
    void anUnparseableApiBaseIsRefused() {
        StripeGatewayProperties properties = enabled(":::not a url:::");

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    /*
     * Disabled means the whole Stripe integration is off, so there is nothing to
     * point anywhere. validate() returns before any of these checks, and that
     * must stay true: a disabled adapter with a stray api-base should not block
     * a boot for a subsystem nobody is using.
     */
    @Test
    void aDisabledGatewayIgnoresTheApiBaseEntirely() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setEnabled(false);
        properties.setApiBase("https://api.stripe.com");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    /*
     * The factory must not swallow what callers add. Each client layers its own
     * api key, idempotency key or connected account on top, and an override that
     * quietly dropped them would break direct charges — the stall would stop
     * being merchant of record and the platform would take the money.
     */
    @Test
    void theFactoryPreservesWhatCallersAddOnTop() {
        StripeGatewayProperties properties = enabled("http://localhost:12111");
        properties.validate();

        RequestOptions options = properties.requestOptions()
                .setApiKey("sk_test_x")
                .setIdempotencyKey("idem-1")
                .setStripeAccount("acct_123")
                .build();

        assertThat(options.getBaseUrl()).isEqualTo("http://localhost:12111");
        assertThat(options.getApiKey()).isEqualTo("sk_test_x");
        assertThat(options.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(options.getStripeAccount()).isEqualTo("acct_123");
    }
}
