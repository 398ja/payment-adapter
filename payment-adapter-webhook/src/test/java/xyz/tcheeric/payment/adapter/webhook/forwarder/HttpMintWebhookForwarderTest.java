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
