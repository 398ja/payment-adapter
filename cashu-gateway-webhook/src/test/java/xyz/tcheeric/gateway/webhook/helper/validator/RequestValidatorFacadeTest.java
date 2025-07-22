package xyz.tcheeric.gateway.webhook.helper.validator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

public class RequestValidatorFacadeTest {

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

    private void stubGatewayApis() {
        wireMockServer.resetAll();
        // Quote lookup
        wireMockServer.stubFor(
                get(urlEqualTo("/quote/search/findByInvoiceId?invoiceId=invoice-123"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"quoteId\":\"quote-123\",\"invoiceId\":\"invoice-123\",\"direction\":\"RECEIVE\"}")));
        // Payment lookup
        wireMockServer.stubFor(
                get(urlEqualTo("/payment/search/findByQuoteId?quoteId=quote-123"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"paymentId\":\"pay-1\",\"quoteId\":\"quote-123\",\"paymentHash\":\"abc123\",\"amount\":10,\"state\":\"PAID\"}")));
    }

    @Test
    public void validateReturnsPayment() {
        stubGatewayApis();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("wid", "A1b2C3d4");
        request.setParameter("type", "payment_received");
        request.setParameter("amountSat", "10");
        request.setParameter("paymentHash", "abc123");
        request.setParameter("externalId", "invoice-123");

        GatewayPayment payment = RequestValidatorFacade.validate(request);
        assertNotNull(payment);
        assertEquals("pay-1", payment.getPaymentId());
    }

    @Test
    public void validateUnknownWid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("wid", "unknown");
        assertThrows(IllegalArgumentException.class, () -> RequestValidatorFacade.validate(request));
    }
}
