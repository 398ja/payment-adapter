package xyz.tcheeric.payment.adapter.stripe.connect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * What the OAuth requests ask Stripe for. Calls the real client methods, not a copy of them, so a change to the
 * request fails here instead of in front of a farm.
 */
class StripeSdkOAuthRequestTest {

    private final StripeSdkConnectClient client = new StripeSdkConnectClient(gateway());

    private static StripeGatewayProperties gateway() {
        StripeGatewayProperties p = new StripeGatewayProperties();
        p.setSecretKey("sk_test_dummy");
        return p;
    }

    /** read_write is mandatory for a platform; Stripe shows an error page for read_only or no scope. */
    @Test
    void authorizeAsksForReadWriteWithOurClientRedirectAndState() {
        Map<String, Object> params = client.oauthAuthorizeParams("ca_123", "https://wallet.example/cb", "st1");

        assertThat(params)
                .containsEntry("response_type", "code")
                .containsEntry("scope", "read_write")
                .containsEntry("client_id", "ca_123")
                .containsEntry("redirect_uri", "https://wallet.example/cb")
                .containsEntry("state", "st1");
    }

    /** The URL the farm's browser opens: Stripe's Connect host, carrying every parameter. */
    @Test
    void authorizeUrlPointsAtStripeAndCarriesTheState() {
        String url = client.oauthAuthorizeUrl("ca_123", "https://wallet.example/cb", "st1");

        assertThat(url).startsWith("https://connect.stripe.com/oauth/authorize?");
        assertThat(url).contains("client_id=ca_123").contains("state=st1").contains("scope=read_write");
    }

    /**
     * The code exchange must make ONE attempt. Stripe revokes the connection when a code is used twice, and the
     * SDK retries failed calls by default, so a timeout Stripe had actually processed would undo the connection.
     */
    @Test
    void tokenExchangeNeverRetries() {
        assertThat(client.oauthTokenOptions().getMaxNetworkRetries()).isZero();
    }
}
