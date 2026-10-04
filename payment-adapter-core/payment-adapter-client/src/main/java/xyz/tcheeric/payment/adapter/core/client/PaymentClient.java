package xyz.tcheeric.payment.adapter.core.client;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.common.QuoteRef;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;


@Slf4j
public class PaymentClient extends AbstractBaseClient<GatewayPayment> {

    public PaymentClient() {
        super("payment", GatewayPayment.class);
    }

    public GatewayPayment getByQuoteId(String quoteId) {
        String url = getUrl() + "/search/findByQuoteId?quoteId=" + quoteId;
        log.info("[payment] GET byQuoteId start: quote_ref={}", QuoteRef.of(quoteId));
        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(url, GatewayPayment.class);
        log.info("[payment] GET byQuoteId success: {}", describe(response.getBody()));
        return response.getBody();
    }

    public GatewayPayment getByPaymentId(String paymentId) {
        String url = getUrl() + "/search/findByPaymentId?paymentId=" + paymentId;
        log.info("Sending request: {}", url);
        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(url, GatewayPayment.class);
        log.info("Received response: {}", describe(response.getBody()));
        return response.getBody();
    }

    public GatewayPayment updatePayment(GatewayPayment payment) {
        String url = getUrl() + "/" + payment.getId();
        log.info("Sending update request: {}", url);
        HttpEntity<GatewayPayment> request = new HttpEntity<>(payment);
        ResponseEntity<GatewayPayment> response = restTemplate.exchange(url, HttpMethod.PUT, request, GatewayPayment.class);
        log.info("Received update response: {}", describe(response.getBody()));
        return response.getBody();
    }
}
