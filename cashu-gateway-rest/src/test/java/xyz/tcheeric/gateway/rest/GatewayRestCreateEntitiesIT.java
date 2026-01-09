package xyz.tcheeric.gateway.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRestCreateEntitiesIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // Verifies that POST /quote creates a GatewayQuote and returns 201 with body and Location header
    @Test
    void createQuote() {
        GatewayQuote req = new GatewayQuote();
        req.setDescription("integration-test");
        req.setAmount(10);
        req.setUnit("sat");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<GatewayQuote> resp = rest.exchange(
                url("/quote"), HttpMethod.POST, new HttpEntity<>(req, headers), GatewayQuote.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).isNotNull();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getId()).isNotNull();
        assertThat(resp.getBody().getDescription()).isEqualTo("integration-test");
    }

    // Verifies that POST /payment creates a GatewayPayment and returns 201 with body and Location header
    @Test
    void createPayment() {
        GatewayPayment req = new GatewayPayment();
        req.setRequest("bolt11:dummy");
        req.setPaymentId("p-it-1");
        req.setQuoteId("q-it-1");
        req.setSourceCurrency("sat");
        req.setAmount(10);
        req.setLightningNetworkFee(1);
        req.setTotalAmount(11);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<GatewayPayment> resp = rest.exchange(
                url("/payment"), HttpMethod.POST, new HttpEntity<>(req, headers), GatewayPayment.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).isNotNull();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getId()).isNotNull();
        assertThat(resp.getBody().getPaymentId()).isEqualTo("p-it-1");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}

