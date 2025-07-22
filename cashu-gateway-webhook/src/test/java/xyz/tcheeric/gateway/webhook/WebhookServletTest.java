package xyz.tcheeric.gateway.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.webhook.helper.validator.RequestValidatorFacade;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebhookServletTest {

    private WireMockServer wireMockServer;
    private WebhookServlet servlet;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8080));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8080);
        servlet = new WebhookServlet();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void doPostReturnsCreatedOnSuccess() throws Exception {
        wireMockServer.stubFor(put(urlEqualTo("/payment/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1}")));

        GatewayPayment payment = new GatewayPayment();
        payment.setId(1L);

        try (MockedStatic<RequestValidatorFacade> mocked = Mockito.mockStatic(RequestValidatorFacade.class)) {
            mocked.when(() -> RequestValidatorFacade.validate(Mockito.any()))
                    .thenReturn(payment);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setParameter("wid", "A1b2C3d4");
            MockHttpServletResponse response = new MockHttpServletResponse();

            servlet.doPost(request, response);

            assertEquals(HttpServletResponse.SC_CREATED, response.getStatus());
            wireMockServer.verify(putRequestedFor(urlEqualTo("/payment/1")));
        }
    }

    @Test
    void doPostReturnsUnauthorizedOnValidationFailure() throws Exception {
        try (MockedStatic<RequestValidatorFacade> mocked = Mockito.mockStatic(RequestValidatorFacade.class)) {
            mocked.when(() -> RequestValidatorFacade.validate(Mockito.any()))
                    .thenThrow(new IllegalArgumentException("invalid"));

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setParameter("wid", "bad");
            MockHttpServletResponse response = new MockHttpServletResponse();

            servlet.doPost(request, response);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        }
    }
}
