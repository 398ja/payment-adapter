package xyz.tcheeric.gateway.webhook.helper.validator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class PhoenixWebhookValidatorTest {

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8080));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8080);
    }

    @AfterAll
    static void stopServer() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void reset() {
        WireMock.reset();
    }

    private void stubQuote(String invoiceId, String quoteId) {
        wireMockServer.stubFor(get(urlEqualTo("/quote/search/findByInvoiceId?invoiceId=" + invoiceId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"quoteId\":\"" + quoteId + "\",\"direction\":\"RECEIVE\"}")));
    }

    private void stubQuoteNotFound(String invoiceId) {
        wireMockServer.stubFor(get(urlEqualTo("/quote/search/findByInvoiceId?invoiceId=" + invoiceId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("null")));
    }

    private void stubPayment(String quoteId, String paymentHash, int amount, State state) {
        wireMockServer.stubFor(get(urlEqualTo("/payment/search/findByQuoteId?quoteId=" + quoteId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"quoteId\":\"" + quoteId + "\",\"paymentHash\":\"" + paymentHash
                                + "\",\"amount\":" + amount + ",\"state\":\"" + state + "\"}")));
    }

    @Test
    void validateSuccess() {
        stubQuote("inv1", "q1");
        stubPayment("q1", "hash", 100, State.PAID);

        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(100);
        req.setPaymentHash("hash");
        req.setExternalId("inv1");

        GatewayPayment result = new PhoenixWebhookValidator(req).validate();

        assertNotNull(result);
        assertEquals("hash", result.getPaymentHash());
        assertEquals(100, result.getAmount());
        assertEquals(State.PAID, result.getState());
    }

    @Test
    void validateQuoteNotFound() {
        stubQuoteNotFound("inv2");

        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(50);
        req.setPaymentHash("hash2");
        req.setExternalId("inv2");

        assertThrows(IllegalArgumentException.class, () -> new PhoenixWebhookValidator(req).validate());
    }

    @Test
    void validateAmountMismatch() {
        stubQuote("inv3", "q3");
        stubPayment("q3", "hash3", 200, State.PAID);

        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(100);
        req.setPaymentHash("hash3");
        req.setExternalId("inv3");

        assertThrows(IllegalArgumentException.class, () -> new PhoenixWebhookValidator(req).validate());
    }

    @Test
    void validatePaymentHashMismatch() {
        stubQuote("inv4", "q4");
        stubPayment("q4", "hash4", 100, State.PAID);

        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(100);
        req.setPaymentHash("wrong");
        req.setExternalId("inv4");

        assertThrows(IllegalArgumentException.class, () -> new PhoenixWebhookValidator(req).validate());
    }

    @Test
    void validateInvalidPaymentState() {
        stubQuote("inv5", "q5");
        stubPayment("q5", "hash5", 100, State.PENDING);

        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(100);
        req.setPaymentHash("hash5");
        req.setExternalId("inv5");

        assertThrows(IllegalArgumentException.class, () -> new PhoenixWebhookValidator(req).validate());
    }
}
