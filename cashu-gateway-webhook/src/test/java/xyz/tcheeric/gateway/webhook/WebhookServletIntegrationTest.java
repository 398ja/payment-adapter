package xyz.tcheeric.gateway.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.rest.CashuGatewaySpringApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CashuGatewaySpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class WebhookServletIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebhookServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new WebhookServlet();
        servlet.init();
    }

    private void createQuoteAndPayment() throws Exception {
        String quoteJson = "{" +
                "\"quoteId\":\"quote-1\"," +
                "\"invoiceId\":\"inv-1\"," +
                "\"expiry\":60," +
                "\"description\":\"test\"," +
                "\"request\":\"req\"," +
                "\"amount\":100," +
                "\"unit\":\"sat\"," +
                "\"state\":\"PENDING\"," +
                "\"direction\":\"RECEIVE\"" +
                "}";
        mockMvc.perform(post("/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteJson))
                .andExpect(status().isCreated());

        String paymentJson = "{" +
                "\"paymentId\":\"pay-1\"," +
                "\"quoteId\":\"quote-1\"," +
                "\"request\":\"req\"," +
                "\"state\":\"PAID\"," +
                "\"amount\":100," +
                "\"paymentHash\":\"hash-1\"," +
                "\"paymentPreimage\":\"preimage\"" +
                "}";
        mockMvc.perform(post("/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson))
                .andExpect(status().isCreated());
    }

    @Test
    void webhookShouldConfirmPayment() throws Exception {
        createQuoteAndPayment();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("wid", "A1b2C3d4");
        request.setParameter("type", "payment_received");
        request.setParameter("amountSat", "100");
        request.setParameter("paymentHash", "hash-1");
        request.setParameter("externalId", "inv-1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        assertThat(response.getStatus()).isEqualTo(201);

        String body = mockMvc.perform(get("/payment/search/findByPaymentId")
                        .param("paymentId", "pay-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        GatewayPayment payment = objectMapper.readValue(body, GatewayPayment.class);
        assertThat(payment.getState().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void webhookWithInvalidIdReturns401() throws Exception {
        createQuoteAndPayment();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("wid", "invalid");
        request.setParameter("type", "payment_received");
        request.setParameter("amountSat", "100");
        request.setParameter("paymentHash", "hash-1");
        request.setParameter("externalId", "inv-1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        assertThat(response.getStatus()).isEqualTo(401);

        String body = mockMvc.perform(get("/payment/search/findByPaymentId")
                        .param("paymentId", "pay-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        GatewayPayment payment = objectMapper.readValue(body, GatewayPayment.class);
        assertThat(payment.getState().name()).isEqualTo("PAID");
    }
}
