package xyz.tcheeric.gateway.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.rest.repository.PaymentRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentControllerIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PaymentRepository paymentRepository;

    // Verifies that GET /payment/search/findByQuoteId returns the payment for an existing quote ID.
    @Test
    void findByQuoteIdReturnsPayment() {
        GatewayPayment payment = GatewayPayment.create(
                "bolt11:test",
                "pay-it-1",
                "quote-it-1",
                "sat",
                25,
                1,
                26,
                "hash-it-1",
                "preimage-it-1");
        paymentRepository.save(payment);

        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(
                url("/payment/search/findByQuoteId?quoteId=quote-it-1"), GatewayPayment.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getQuoteId()).isEqualTo("quote-it-1");
        assertThat(response.getBody().getPaymentId()).isEqualTo("pay-it-1");
        assertThat(response.getBody().getPaymentHash()).isEqualTo("hash-it-1");
        assertThat(response.getBody().getPaymentPreimage()).isEqualTo("preimage-it-1");
    }

    // Verifies that GET /payment/search/findByQuoteId returns 404 when no payment matches the quote ID.
    @Test
    void findByQuoteIdReturnsNotFoundWhenMissing() {
        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(
                url("/payment/search/findByQuoteId?quoteId=missing-quote"), GatewayPayment.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
