package xyz.tcheeric.payment.adapter.core.client;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;


@Slf4j
public class QuoteClient extends AbstractBaseClient<GatewayQuote> {

    public QuoteClient() {
        super("quote", GatewayQuote.class);
    }

    public GatewayQuote getByInvoiceId(String invoiceId) {
        String url = getUrl() + "/search/findByInvoiceId?invoiceId=" + invoiceId;
        log.info("Sending request: {}", url);
        ResponseEntity<GatewayQuote> response = restTemplate.getForEntity(url, GatewayQuote.class);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }

    public GatewayQuote updateQuote(GatewayQuote quote) {
        String url = getUrl() + "/" + quote.getId();
        log.info("Sending update request: {}", url);
        HttpEntity<GatewayQuote> request = new HttpEntity<>(quote);
        ResponseEntity<GatewayQuote> response = restTemplate.exchange(url, HttpMethod.PUT, request, GatewayQuote.class);
        log.info("Received update response: {}", response.getBody());
        return response.getBody();
    }
}
