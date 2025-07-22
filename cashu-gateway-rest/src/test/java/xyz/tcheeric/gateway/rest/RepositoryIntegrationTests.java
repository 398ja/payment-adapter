package xyz.tcheeric.gateway.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EntityScan("xyz.tcheeric.gateway.model.entity")
class RepositoryIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;

    @BeforeEach
    void setup() {
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void quoteCrudLifecycle() {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote-1");
        quote.setInvoiceId("inv-1");
        quote.setExpiry(60);
        quote.setDescription("desc");
        quote.setRequest("req");
        quote.setAmount(100);
        quote.setUnit("SAT");
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);

        ResponseEntity<GatewayQuote> post = restTemplate.postForEntity(baseUrl + "/quote", quote, GatewayQuote.class);
        assertEquals(HttpStatus.CREATED, post.getStatusCode());
        GatewayQuote created = post.getBody();
        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals("quote-1", created.getQuoteId());

        ResponseEntity<GatewayQuote> fetchedResp = restTemplate.getForEntity(
                baseUrl + "/quote/search/findByQuoteId?quoteId=" + created.getQuoteId(),
                GatewayQuote.class);
        assertEquals(HttpStatus.OK, fetchedResp.getStatusCode());
        GatewayQuote fetched = fetchedResp.getBody();
        assertNotNull(fetched);
        assertEquals(created.getId(), fetched.getId());

        fetched.setDescription("updated");
        restTemplate.put(baseUrl + "/quote/" + fetched.getId(), fetched);
        ResponseEntity<GatewayQuote> updatedResp = restTemplate.getForEntity(
                baseUrl + "/quote/" + fetched.getId(), GatewayQuote.class);
        assertEquals(HttpStatus.OK, updatedResp.getStatusCode());
        assertEquals("updated", updatedResp.getBody().getDescription());

        restTemplate.delete(baseUrl + "/quote/" + fetched.getId());
        ResponseEntity<String> afterDelete = restTemplate.getForEntity(
                baseUrl + "/quote/" + fetched.getId(), String.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }

    @Test
    void paymentCrudLifecycle() {
        GatewayPayment payment = new GatewayPayment();
        payment.setPaymentId("payment-1");
        payment.setQuoteId("quote-payment");
        payment.setRequest("req-p");
        payment.setState(State.PENDING);
        payment.setSourceCurrency("SAT");
        payment.setAmount(10);
        payment.setLightningNetworkFee(1);
        payment.setTotalAmount(11);
        payment.setPaymentHash("hash");
        payment.setPaymentPreimage("preimage");

        ResponseEntity<GatewayPayment> post = restTemplate.postForEntity(baseUrl + "/payment", payment, GatewayPayment.class);
        assertEquals(HttpStatus.CREATED, post.getStatusCode());
        GatewayPayment created = post.getBody();
        assertNotNull(created);
        assertNotNull(created.getId());

        ResponseEntity<GatewayPayment> fetchedResp = restTemplate.getForEntity(
                baseUrl + "/payment/search/findByPaymentId?paymentId=" + created.getPaymentId(),
                GatewayPayment.class);
        assertEquals(HttpStatus.OK, fetchedResp.getStatusCode());
        GatewayPayment fetched = fetchedResp.getBody();
        assertNotNull(fetched);
        assertEquals(created.getId(), fetched.getId());

        fetched.setState(State.PAID);
        restTemplate.put(baseUrl + "/payment/" + fetched.getId(), fetched);
        ResponseEntity<GatewayPayment> updatedResp = restTemplate.getForEntity(
                baseUrl + "/payment/" + fetched.getId(), GatewayPayment.class);
        assertEquals(HttpStatus.OK, updatedResp.getStatusCode());
        assertEquals(State.PAID, updatedResp.getBody().getState());

        restTemplate.delete(baseUrl + "/payment/" + fetched.getId());
        ResponseEntity<String> afterDelete = restTemplate.getForEntity(
                baseUrl + "/payment/" + fetched.getId(), String.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }
}
