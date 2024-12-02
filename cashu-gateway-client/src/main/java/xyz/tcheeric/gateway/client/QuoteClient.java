package xyz.tcheeric.gateway.client;


import lombok.extern.java.Log;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

import java.util.logging.Level;

@Log
public class QuoteClient extends AbstractBaseClient<GatewayQuote> {

    public QuoteClient() {
        super("quote", GatewayQuote.class);
    }

    public GatewayQuote getByInvoiceId(String invoiceId) {
        String url = getBaseUrl() + "/search/findByInvoiceId?invoiceId=" + invoiceId;
        log.log(Level.INFO, "Sending request: {0}", url);
        ResponseEntity<GatewayQuote> response = restTemplate.getForEntity(url, GatewayQuote.class);
        log.log(Level.INFO, "Received response: {0}", response.getBody());
        return response.getBody();
    }
}
