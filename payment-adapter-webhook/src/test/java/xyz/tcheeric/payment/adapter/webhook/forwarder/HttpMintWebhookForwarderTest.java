package xyz.tcheeric.payment.adapter.webhook.forwarder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HttpMintWebhookForwarder using WireMock.
 */
class HttpMintWebhookForwarderTest {

    private WireMockServer wireMockServer;
    private HttpMintWebhookForwarder forwarder;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        forwarder = new HttpMintWebhookForwarder();
        ReflectionTestUtils.setField(forwarder, "enabled", true);
        ReflectionTestUtils.setField(forwarder, "mintWebhookUrl",
                "http://localhost:" + wireMockServer.port() + "/webhook/payment");
        ReflectionTestUtils.setField(forwarder, "webhookSecret", "");
        ReflectionTestUtils.setField(forwarder, "timeoutMs", 5000);
        ReflectionTestUtils.setField(forwarder, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(forwarder, "initialDelayMs", 100);
        ReflectionTestUtils.setField(forwarder, "retryMultiplier", 2.0);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void notifyPaymentReceived_shouldSucceedOn200() {
        // Given
        stubFor(post(urlEqualTo("/webhook/payment"))
                .willReturn(aResponse().withStatus(200)));

        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote123", 1000, "preimage456");

        // When
        boolean result = forwarder.notifyPaymentReceived(notification);

        // Then
        assertTrue(result);
        verify(postRequestedFor(urlEqualTo("/webhook/payment"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Idempotency-Key", equalTo("bolt11:quote123")));
    }

    @Test
    void notifyPaymentReceived_shouldRetryOnFailure() {
        // Given - fail twice, then succeed
        stubFor(post(urlEqualTo("/webhook/payment"))
                .inScenario("Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("First Failure"));

        stubFor(post(urlEqualTo("/webhook/payment"))
                .inScenario("Retry")
                .whenScenarioStateIs("First Failure")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Second Failure"));

        stubFor(post(urlEqualTo("/webhook/payment"))
                .inScenario("Retry")
                .whenScenarioStateIs("Second Failure")
                .willReturn(aResponse().withStatus(200)));

        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote456", 2000, "preimage789");

        // When
        boolean result = forwarder.notifyPaymentReceived(notification);

        // Then
        assertTrue(result);
        verify(3, postRequestedFor(urlEqualTo("/webhook/payment")));
    }

    @Test
    void notifyPaymentReceived_shouldFailAfterMaxRetries() {
        // Given - always fail
        stubFor(post(urlEqualTo("/webhook/payment"))
                .willReturn(aResponse().withStatus(500)));

        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote789", 3000, "preimage000");

        // When
        boolean result = forwarder.notifyPaymentReceived(notification);

        // Then
        assertFalse(result);
        verify(3, postRequestedFor(urlEqualTo("/webhook/payment")));
    }

    @Test
    void notifyPaymentReceived_shouldReturnFalseWhenDisabled() {
        // Given
        ReflectionTestUtils.setField(forwarder, "enabled", false);
        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote123", 1000, "preimage");

        // When
        boolean result = forwarder.notifyPaymentReceived(notification);

        // Then
        assertFalse(result);
        verify(0, postRequestedFor(urlEqualTo("/webhook/payment")));
    }

    @Test
    void notifyPaymentReceived_shouldIncludeSignatureWhenSecretConfigured() {
        // Given
        ReflectionTestUtils.setField(forwarder, "webhookSecret", "mysecret");
        stubFor(post(urlEqualTo("/webhook/payment"))
                .willReturn(aResponse().withStatus(200)));

        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote123", 1000, "preimage456");

        // When
        boolean result = forwarder.notifyPaymentReceived(notification);

        // Then
        assertTrue(result);
        verify(postRequestedFor(urlEqualTo("/webhook/payment"))
                .withHeader("X-Webhook-Signature", matching(".+")));
    }

    /**
     * The mint requires X-Webhook-Timestamp and refuses the webhook without it.
     *
     * <p>Until this was fixed the adapter sent only the signature, so every payment
     * notification came back 401 "Invalid signature" and vouchers stayed UNFUNDED however many
     * times the forwarder retried. The mint's log named the cause exactly: "Webhook timestamp
     * missing and cashu.mint.webhook.require-timestamp=true".
     */
    @Test
    void notifyPaymentReceived_shouldSendTheTimestampHeaderTheMintRequires() {
        ReflectionTestUtils.setField(forwarder, "webhookSecret", "mysecret");
        stubFor(post(urlEqualTo("/webhook/payment")).willReturn(aResponse().withStatus(200)));

        forwarder.notifyPaymentReceived(
                PaymentNotification.forBolt11("quote123", 1000, "preimage456"));

        verify(postRequestedFor(urlEqualTo("/webhook/payment"))
                .withHeader("X-Webhook-Timestamp", matching("[0-9]+")));
    }

    /**
     * The signature covers "&lt;timestamp&gt;.&lt;body&gt;", not the body alone.
     *
     * <p>This is the half that is easy to get wrong and impossible to see from a passing
     * header assertion: sending the timestamp as a bare header, with the MAC still computed
     * over the body only, would satisfy the test above and still be rejected by the mint --
     * and would leave the replay window the timestamp exists to close, since an attacker could
     * edit the header without invalidating the signature.
     *
     * <p>Recomputed here with the mint's own construction rather than the adapter's, so the two
     * cannot drift into agreeing with each other while both being wrong.
     */
    @Test
    void notifyPaymentReceived_shouldSignTheTimestampTogetherWithTheBody() throws Exception {
        String secret = "mysecret";
        ReflectionTestUtils.setField(forwarder, "webhookSecret", secret);
        stubFor(post(urlEqualTo("/webhook/payment")).willReturn(aResponse().withStatus(200)));

        forwarder.notifyPaymentReceived(
                PaymentNotification.forBolt11("quote123", 1000, "preimage456"));

        com.github.tomakehurst.wiremock.http.Request sent =
                findAll(postRequestedFor(urlEqualTo("/webhook/payment"))).get(0);
        String timestamp = sent.getHeader("X-Webhook-Timestamp");
        String signature = sent.getHeader("X-Webhook-Signature");

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "." + sent.getBodyAsString())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(expected, signature,
                "signature must cover <timestamp>.<body>, which is what the mint verifies");
    }

    @Test
    void isEnabled_shouldReturnConfiguredValue() {
        // Given
        ReflectionTestUtils.setField(forwarder, "enabled", true);

        // Then
        assertTrue(forwarder.isEnabled());

        // When
        ReflectionTestUtils.setField(forwarder, "enabled", false);

        // Then
        assertFalse(forwarder.isEnabled());
    }
}
