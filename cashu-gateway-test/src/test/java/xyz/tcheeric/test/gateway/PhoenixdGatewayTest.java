package xyz.tcheeric.test.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.common.util.Configuration;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.phoenixd.PhoenixdGateway;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
@NoArgsConstructor
public class PhoenixdGatewayTest {

    private PhoenixdGateway gateway;

    private WireMockServer phoenixMock;
    private WireMockServer apiMock;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Configuration configuration = new Configuration("phoenixd", PhoenixdGatewayTest.class.getClassLoader().getResource("gw-test.properties"));

    @BeforeEach
    public void init() {
        phoenixMock = new WireMockServer(WireMockConfiguration.options().port(9740));
        apiMock = new WireMockServer(WireMockConfiguration.options().port(8080));
        phoenixMock.start();
        apiMock.start();
        gateway = new PhoenixdGateway();
    }

    @AfterEach
    public void shutdown() {
        phoenixMock.stop();
        apiMock.stop();
    }

    @Test
    public void testCreateMintQuote() throws Exception {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/invoice"))
                .willReturn(WireMock.okJson("{\"serialized\":\"lninvoice\",\"amountSat\":10}")));
        phoenixMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/lightning-address"))
                .willReturn(WireMock.okJson("{\"lightningAddress\":\"bob@ln\"}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/quote"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":1}"))));

        System.setProperty("wid", "testCreateMintQuote");
        String quoteId = gateway.createMintQuote(10, "testCreateMintQuote");

        JsonNode body = mapper.readTree(apiMock.getServeEvents().get(0).getRequest().getBodyAsString());
        apiMock.stubFor(WireMock.get(WireMock.urlEqualTo("/quote/search/findByQuoteId?quoteId=" + quoteId))
                .willReturn(WireMock.aResponse().withHeader("Content-Type", "application/json").withBody(body.toString())));

        GatewayQuote quote = new QuoteClient().getByEntityId(quoteId);

        assertNotNull(quote);
        assertEquals(quoteId, quote.getQuoteId());
        assertEquals(State.PENDING, quote.getState());
        log.debug("LN Invoice: {}", quote.getRequest());
    }

    @Test
    public void testPayInvoice() throws Exception {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/pay"))
                .willReturn(WireMock.okJson("{\"paymentId\":\"pid\",\"paymentPreimage\":\"pre\",\"paymentHash\":\"hash\",\"recipientAmountSat\":10,\"routingFeeSat\":1}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/payment"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody("{\"id\":1}"))));

        String quoteId = gateway.createMeltQuote(10, "bob@ln", "testPayInvoice");
        String paymentId = gateway.pay(quoteId);

        JsonNode paymentBody = mapper.readTree(
                apiMock.getServeEvents().getServeEvents().stream()
                        .filter(e -> "/payment".equals(e.getRequest().getUrl()))
                        .findFirst().get().getRequest().getBodyAsString());
        apiMock.stubFor(WireMock.get(WireMock.urlEqualTo("/payment/search/findByPaymentId?paymentId=" + paymentId))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody(paymentBody.toString())));

        GatewayPayment payment = new PaymentClient().getByEntityId(paymentId);

        assertNotNull(payment);
        assertEquals(paymentId, payment.getPaymentId());
        assertEquals(State.PAID, payment.getState());
    }

    @Test
    public void testPayInvoiceFailure() {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/pay"))
                .willReturn(WireMock.okJson("{\"reason\":\"FAILURE\"}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/payment"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody("{\"id\":1}"))));

        String quoteId = gateway.createMeltQuote(10, "bob@ln", "errorPay");

        assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
    }

    @Test
    public void testSupports()  {
        // Arrange & Act
        Gateway gateway = new PhoenixdGateway();

        // Assert
        assertTrue(gateway.supports(PaymentMethod.BOLT11));
        assertTrue(gateway.supports(PaymentMethod.BOLT12));
        assertTrue(gateway.supports(PaymentMethod.ON_CHAIN));
        assertFalse(gateway.supports(PaymentMethod.CASH));
        assertFalse(gateway.supports(PaymentMethod.PAYMENT_API));
        assertFalse(gateway.supports(PaymentMethod.CREDIT_CARD));
        assertFalse(gateway.supports(PaymentMethod.MOBILE_MONEY));
        assertFalse(gateway.supports(PaymentMethod.MOCK));
    }
}
