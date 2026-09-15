package xyz.tcheeric.payment.adapter.webhook.forwarder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The webhook contract this forwarder has to satisfy against gateway-core.
 *
 * <p>{@code PaymentWebhookValidator} requires {@code X-Webhook-Timestamp} unconditionally and
 * verifies the MAC over {@code "<unix-seconds>.<body>"}. A request carrying only the signature is
 * refused with {@code reason=missing_timestamp}, so the notification never lands.
 *
 * <p>This is the same defect that was found in the mint forwarder, in a second sender that was
 * missed the first time. There were no tests for this class at all, which is why.
 */
class HttpGatewayWebhookForwarderTest {

    private static final String SECRET = "mysecret";

    private WireMockServer wireMockServer;
    private HttpGatewayWebhookForwarder forwarder;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        forwarder = new HttpGatewayWebhookForwarder();
        ReflectionTestUtils.setField(forwarder, "enabled", true);
        ReflectionTestUtils.setField(forwarder, "gatewayWebhookUrl",
                "http://localhost:" + wireMockServer.port() + "/internal/webhook/payment");
        ReflectionTestUtils.setField(forwarder, "webhookSecret", SECRET);
        ReflectionTestUtils.setField(forwarder, "timeoutMs", 5000);
        ReflectionTestUtils.setField(forwarder, "maxRetryAttempts", 1);
        ReflectionTestUtils.setField(forwarder, "initialDelayMs", 10);
        ReflectionTestUtils.setField(forwarder, "retryMultiplier", 2.0);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    /** gateway-core refuses a webhook with no timestamp header, whatever the signature says. */
    @Test
    void notifyPaymentConfirmed_shouldSendTheTimestampHeaderGatewayCoreRequires() {
        stubFor(post(urlEqualTo("/internal/webhook/payment"))
                .willReturn(aResponse().withStatus(200)));

        forwarder.notifyPaymentConfirmed(
                PaymentNotification.forBolt11("quote123", 1000, "preimage456"));

        verify(postRequestedFor(urlEqualTo("/internal/webhook/payment"))
                .withHeader("X-Webhook-Timestamp", matching("[0-9]+")));
    }

    /**
     * The signature covers {@code "<timestamp>.<body>"}, not the body alone.
     *
     * <p>Sending the timestamp as a bare header while signing only the body would satisfy the
     * header assertion above, still be rejected by gateway-core, and leave the replay window the
     * timestamp exists to close -- an attacker could edit the header without invalidating the
     * signature.
     *
     * <p>Recomputed with gateway-core's construction rather than by calling this class's own
     * helper, so the two cannot drift into agreeing with each other while both are wrong.
     */
    @Test
    void notifyPaymentConfirmed_shouldSignTheTimestampTogetherWithTheBody() throws Exception {
        stubFor(post(urlEqualTo("/internal/webhook/payment"))
                .willReturn(aResponse().withStatus(200)));

        forwarder.notifyPaymentConfirmed(
                PaymentNotification.forBolt11("quote123", 1000, "preimage456"));

        com.github.tomakehurst.wiremock.http.Request sent =
                findAll(postRequestedFor(urlEqualTo("/internal/webhook/payment"))).get(0);
        String timestamp = sent.getHeader("X-Webhook-Timestamp");
        String signature = sent.getHeader("X-Webhook-Signature");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "." + sent.getBodyAsString())
                        .getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, signature,
                "signature must cover <timestamp>.<body>, which is what gateway-core verifies");
    }
}
