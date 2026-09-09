package xyz.tcheeric.payment.adapter.stripe.connect.config;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two Stripe flags that must be set together, and the failure when they are not.
 *
 * <h2>What this prevents</h2>
 *
 * <p>{@code stripe.connect.enabled} turns on connected accounts.
 * {@code stripe.enabled} turns on the gateway beans, including the
 * {@code /api/v1/checkout} controller that is {@code @ConditionalOnProperty} on
 * it. Setting only the first produces a configuration that looks entirely
 * coherent and fails at the last possible moment:
 *
 * <ul>
 *   <li>the stall connects Stripe and is told it worked;</li>
 *   <li>it publishes {@code acceptsCards}, so every shopper sees a Buy button;</li>
 *   <li>and the purchase answers <b>404</b>, because the controller was never
 *       registered.</li>
 * </ul>
 *
 * <p>Nothing logs a reason — no bean failed, one simply does not exist — so the
 * 404 lands on a shopper under an amount they have already typed, and the stall
 * cannot see it. Found from that end, which is the expensive one.
 *
 * <p>The reverse is deliberately allowed: gateway on with Connect off is a
 * platform taking payments for itself, which this adapter supports.
 */
class StripeFlagPairingTest {

    private static StripeConnectConfig config(boolean gateway, boolean connect) {
        StripeGatewayProperties gatewayProperties = new StripeGatewayProperties();
        gatewayProperties.setEnabled(gateway);

        StripeConnectProperties connectProperties = new StripeConnectProperties();
        connectProperties.setEnabled(connect);

        return new StripeConnectConfig(connectProperties, gatewayProperties);
    }

    /** THE TRAP. Connect alone advertises a purchase route that does not exist. */
    @Test
    void connectWithoutTheGatewayRefusesToStart() {
        assertThatThrownBy(() -> config(false, true).requireGatewayWhenConnectIsEnabled())
                .isInstanceOf(IllegalStateException.class)
                // Names both flags and the consequence: a refusal a reader
                // cannot act on is one they will work around.
                .hasMessageContaining("stripe.enabled=true")
                .hasMessageContaining("404")
                .hasMessageContaining("STRIPE_ENABLED");
    }

    /** Both on: the working configuration. */
    @Test
    void bothEnabledStarts() {
        assertThatCode(() -> config(true, true).requireGatewayWhenConnectIsEnabled())
                .doesNotThrowAnyException();
    }

    /**
     * Gateway WITHOUT Connect is legal, and this is the assertion that keeps the
     * guard from becoming an obstacle. A platform charging on its own account
     * needs no connected accounts at all; refusing that would break a supported
     * deployment to prevent a different mistake.
     */
    @Test
    void theGatewayAloneIsAValidPlatformConfiguration() {
        assertThatCode(() -> config(true, false).requireGatewayWhenConnectIsEnabled())
                .doesNotThrowAnyException();
    }

    /** Stripe off entirely: nothing to check. */
    @Test
    void bothDisabledStarts() {
        assertThatCode(() -> config(false, false).requireGatewayWhenConnectIsEnabled())
                .doesNotThrowAnyException();
    }
}
