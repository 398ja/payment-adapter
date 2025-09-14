package xyz.tcheeric.gateway.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;


@Slf4j
public class PaymentClient extends AbstractBaseClient<GatewayPayment> {

    public PaymentClient() {
        super("payment", GatewayPayment.class);
    }

    public GatewayPayment getByQuoteId(String quoteId) {
        String url = getUrl() + "/search/findByQuoteId?quoteId=" + quoteId;
        log.info("Sending request: {}", url);
        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(url, GatewayPayment.class);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }

    public GatewayPayment updatePayment(GatewayPayment payment) {
        String url = getUrl() + "/" + payment.getId();
        log.info("Sending update request: {}", url);
        HttpEntity<GatewayPayment> request = new HttpEntity<>(payment);
        ResponseEntity<GatewayPayment> response = restTemplate.exchange(url, HttpMethod.PUT, request, GatewayPayment.class);
        log.info("Received update response: {}", response.getBody());
        return response.getBody();
    }
}
