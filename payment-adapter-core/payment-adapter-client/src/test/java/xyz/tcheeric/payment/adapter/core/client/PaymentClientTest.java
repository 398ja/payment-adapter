package xyz.tcheeric.payment.adapter.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class PaymentClientTest {

    private PaymentClient paymentClient;
    private MockRestServiceServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        paymentClient = new PaymentClient();
        server = MockRestServiceServer.bindTo(paymentClient.getRestTemplate()).build();
    }

    @Test
    void getByQuoteId() throws Exception {
        GatewayPayment expected = new GatewayPayment();
        expected.setId(1L);
        expected.setQuoteId("quote-123");
        expected.setPaymentId("payment-1");

        String responseBody = mapper.writeValueAsString(expected);

        server.expect(requestTo("http://localhost:8080/payment/search/findByQuoteId?quoteId=quote-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        GatewayPayment actual = paymentClient.getByQuoteId("quote-123");

        server.verify();
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getQuoteId()).isEqualTo(expected.getQuoteId());
        assertThat(actual.getPaymentId()).isEqualTo(expected.getPaymentId());
    }

    @Test
    void create() throws Exception {
        GatewayPayment request = new GatewayPayment();
        request.setAmount(10);
        request.setPaymentId("p2");

        String responseBody = mapper.writeValueAsString(request);

        server.expect(requestTo("http://localhost:8080/payment"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        GatewayPayment response = paymentClient.create(request);

        server.verify();
        assertThat(response.getAmount()).isEqualTo(request.getAmount());
        assertThat(response.getPaymentId()).isEqualTo(request.getPaymentId());
    }

    @Test
    void updatePayment() throws Exception {
        GatewayPayment payment = new GatewayPayment();
        payment.setId(5L);
        payment.setState(State.PAID);

        String responseBody = mapper.writeValueAsString(payment);

        server.expect(requestTo("http://localhost:8080/payment/5"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        GatewayPayment updated = paymentClient.updatePayment(payment);

        server.verify();
        assertThat(updated.getId()).isEqualTo(payment.getId());
        assertThat(updated.getState()).isEqualTo(payment.getState());
    }
}
