package xyz.tcheeric.payment.adapter.stripe.connect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StripeSdkConnectClient#retrievePaymentIntent} against a real stripe-mock, so the request the
 * SDK builds (path, {@code Stripe-Account}, parsing) is exercised rather than mocked away.
 *
 * <p>Opt-in: set {@code STRIPE_MOCK_URL}, e.g. {@code http://localhost:12111}. stripe-mock answers
 * every id with its fixture, so this proves the read, not the verdict, which
 * {@link PaymentReferenceVerifierTest} owns.
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_MOCK_URL", matches = "https?://.+")
class StripeSdkPaymentIntentMockTest {

    @Test
    void readsAPaymentIntentOnAConnectedAccount() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setSecretKey("sk_test_123");
        properties.setApiBase(System.getenv("STRIPE_MOCK_URL"));

        var intent = new StripeSdkConnectClient(properties).retrievePaymentIntent("acct_123", "pi_123");

        assertThat(intent).isPresent();
        assertThat(intent.get().id()).startsWith("pi_");
        assertThat(intent.get().currency()).isNotBlank();
        assertThat(intent.get().amount()).isPositive();
    }
}
