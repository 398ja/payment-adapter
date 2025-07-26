package xyz.tcheeric.gateway.phoenixd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.gateway.common.Gateway;

@NoArgsConstructor
public class PhoenixdGatewayTest {

    private PhoenixdGateway gateway;

    private WireMockServer phoenixMock;
    private WireMockServer apiMock;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void init() {
        phoenixMock = new WireMockServer(WireMockConfiguration.options().port(19740));
        apiMock = new WireMockServer(WireMockConfiguration.options().port(18080));
        phoenixMock.start();
        apiMock.start();
        gateway = new PhoenixdGateway();
    }

    @AfterEach
    public void shutdown() {
        phoenixMock.stop();
        apiMock.stop();
    }

/*
    @Test
    public void testCreateMintQuote() throws Exception {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/invoice"))
                .willReturn(WireMock.okJson("{\"serialized\":\"lninvoice\",\"amountSat\":10}")));
        phoenixMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/lightning-address"))
                .willReturn(WireMock.okJson("{\"lightningAddress\":\"bob@ln\"}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/quote"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":1}")));

        System.setProperty("wid", "testCreateMintQuote");
        String quoteId = gateway.createMintQuote(10, "testCreateMintQuote");

        JsonNode body = mapper.readTree(apiMock.getServeEvents().getRequests().get(0).getRequest().getBodyAsString());
        apiMock.stubFor(WireMock.get(WireMock.urlEqualTo("/quote/search/findByQuoteId?quoteId=" + quoteId))
                .willReturn(WireMock.aResponse().withHeader("Content-Type", "application/json").withBody(body.toString())));

        GatewayQuote quote = new QuoteClient().getByEntityId(quoteId);

        Assertions.assertNotNull(quote);
        Assertions.assertEquals(quoteId, quote.getQuoteId());
        Assertions.assertEquals(State.PENDING, quote.getState());
        log.debug("LN Invoice: {}", quote.getRequest());
    }

    @Test
    public void testPayInvoice() throws Exception {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/pay"))
                .willReturn(WireMock.okJson("{\"paymentId\":\"pid\",\"paymentPreimage\":\"pre\",\"paymentHash\":\"hash\",\"recipientAmountSat\":10,\"routingFeeSat\":1}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/payment"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody("{\"id\":1}")));

        String quoteId = gateway.createMeltQuote(10, "bob@ln", "testPayInvoice");
        String paymentId = gateway.pay(quoteId);

        JsonNode paymentBody = mapper.readTree(
                apiMock.getServeEvents().getServeEvents().stream()
                        .filter(e -> "/payment".equals(e.getRequest().getUrl()))
                        .findFirst().get().getRequest().getBodyAsString());
        apiMock.stubFor(WireMock.get(WireMock.urlEqualTo("/payment/search/findByPaymentId?paymentId=" + paymentId))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody(paymentBody.toString())));

        GatewayPayment payment = new PaymentClient().getByEntityId(paymentId);

        Assertions.assertNotNull(payment);
        Assertions.assertEquals(paymentId, payment.getPaymentId());
        Assertions.assertEquals(State.PAID, payment.getState());
    }

    @Test
    public void testPayInvoiceFailure() {
        phoenixMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/pay"))
                .willReturn(WireMock.okJson("{\"reason\":\"FAILURE\"}")));

        apiMock.stubFor(WireMock.post(WireMock.urlEqualTo("/payment"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type","application/json").withBody("{\"id\":1}")));

        String quoteId = gateway.createMeltQuote(10, "bob@ln", "errorPay");

        Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
    }
*/

    @Test
    public void testSupports()  {
        // Arrange & Act
        Gateway gateway = new PhoenixdGateway();

        // Assert
        Assertions.assertTrue(gateway.supports(PaymentMethod.BOLT11));
        Assertions.assertTrue(gateway.supports(PaymentMethod.BOLT12));
        Assertions.assertTrue(gateway.supports(PaymentMethod.ON_CHAIN));
        Assertions.assertFalse(gateway.supports(PaymentMethod.CASH));
        Assertions.assertFalse(gateway.supports(PaymentMethod.PAYMENT_API));
        Assertions.assertFalse(gateway.supports(PaymentMethod.CREDIT_CARD));
        Assertions.assertFalse(gateway.supports(PaymentMethod.MOBILE_MONEY));
        Assertions.assertFalse(gateway.supports(PaymentMethod.MOCK));
    }
}
