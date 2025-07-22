package xyz.tcheeric.gateway.client;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;


@Slf4j
public class QuoteClient extends AbstractBaseClient<GatewayQuote> {

    public QuoteClient() {
        super("quote", GatewayQuote.class);
    }

    public GatewayQuote getByInvoiceId(String invoiceId) {
        String url = getBaseUrl() + "/search/findByInvoiceId?invoiceId=" + invoiceId;
        log.info("Sending request: {}", url);
        ResponseEntity<GatewayQuote> response = restTemplate.getForEntity(url, GatewayQuote.class);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }
}
