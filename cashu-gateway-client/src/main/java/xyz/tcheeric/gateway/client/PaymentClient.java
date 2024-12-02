package xyz.tcheeric.gateway.client;

import lombok.extern.java.Log;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;

import java.util.logging.Level;

@Log
public class PaymentClient extends AbstractBaseClient<GatewayPayment> {

    public PaymentClient() {
        super("payment", GatewayPayment.class);
    }

    public GatewayPayment getByLnInvoice(String lnInvoice) {
        String url = getBaseUrl() + "/search/findByLnInvoice?lnInvoice=" + lnInvoice;
        log.log(Level.INFO, "Sending request: {0}", url);
        ResponseEntity<GatewayPayment> response = restTemplate.getForEntity(url, GatewayPayment.class);
        log.log(Level.INFO, "Received response: {0}", response.getBody());
        return response.getBody();
    }

    public GatewayPayment updatePayment(GatewayPayment payment) {
        String url = getBaseUrl() + "/" + payment.getId();
        log.log(Level.INFO, "Sending update request: {0}", url);
        HttpEntity<GatewayPayment> request = new HttpEntity<>(payment);
        ResponseEntity<GatewayPayment> response = restTemplate.exchange(url, HttpMethod.PUT, request, GatewayPayment.class);
        log.log(Level.INFO, "Received update response: {0}", response.getBody());
        return response.getBody();
    }
}
