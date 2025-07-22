package xyz.tcheeric.gateway.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuoteClientTest {

    private RestTemplate restTemplate;
    private QuoteClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new QuoteClient();
        restTemplate = mock(RestTemplate.class);
        Field field = AbstractBaseClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(client, restTemplate);
    }

    @Test
    void getByInvoiceId_buildsCorrectUrl() {
        when(restTemplate.getForEntity(any(String.class), eq(GatewayQuote.class)))
                .thenReturn(new ResponseEntity<>(new GatewayQuote(), HttpStatus.OK));

        client.getByInvoiceId("invoice123");

        verify(restTemplate).getForEntity(
                eq("http://localhost:8080/quote/search/findByInvoiceId?invoiceId=invoice123"),
                eq(GatewayQuote.class));
    }

    @Test
    void getByEntityId_buildsCorrectUrl() {
        when(restTemplate.getForEntity(any(String.class), eq(GatewayQuote.class)))
                .thenReturn(new ResponseEntity<>(new GatewayQuote(), HttpStatus.OK));

        client.getByEntityId("qid");

        verify(restTemplate).getForEntity(
                eq("http://localhost:8080/quote/search/findByQuoteId?quoteId=qid"),
                eq(GatewayQuote.class));
    }
}
