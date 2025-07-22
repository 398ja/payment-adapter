package xyz.tcheeric.gateway.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentClientTest {

    private RestTemplate restTemplate;
    private PaymentClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new PaymentClient();
        restTemplate = mock(RestTemplate.class);
        Field field = AbstractBaseClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(client, restTemplate);
    }

    @Test
    void getByQuoteId_buildsCorrectUrl() {
        when(restTemplate.getForEntity(any(String.class), eq(GatewayPayment.class)))
                .thenReturn(new ResponseEntity<>(new GatewayPayment(), HttpStatus.OK));

        client.getByQuoteId("qid");

        verify(restTemplate).getForEntity(
                eq("http://localhost:8080/payment/search/findByQuoteId?quoteId=qid"),
                eq(GatewayPayment.class));
    }

    @Test
    void getByEntityId_buildsCorrectUrl() {
        when(restTemplate.getForEntity(any(String.class), eq(GatewayPayment.class)))
                .thenReturn(new ResponseEntity<>(new GatewayPayment(), HttpStatus.OK));

        client.getByEntityId("pid");

        verify(restTemplate).getForEntity(
                eq("http://localhost:8080/payment/search/findByPaymentId?paymentId=pid"),
                eq(GatewayPayment.class));
    }

    @Test
    void create_postsEntity() {
        GatewayPayment payment = new GatewayPayment();
        when(restTemplate.exchange(eq("http://localhost:8080/payment"), eq(HttpMethod.POST), any(HttpEntity.class), eq(GatewayPayment.class)))
                .thenReturn(new ResponseEntity<>(payment, HttpStatus.OK));

        client.create(payment);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://localhost:8080/payment"), eq(HttpMethod.POST), captor.capture(), eq(GatewayPayment.class));
        assertThat(captor.getValue().getBody()).isEqualTo(payment);
    }

    @Test
    void updatePayment_putsEntity() {
        GatewayPayment payment = new GatewayPayment();
        payment.setId(5L);
        when(restTemplate.exchange(eq("http://localhost:8080/payment/5"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(GatewayPayment.class)))
                .thenReturn(new ResponseEntity<>(payment, HttpStatus.OK));

        client.updatePayment(payment);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://localhost:8080/payment/5"), eq(HttpMethod.PUT), captor.capture(), eq(GatewayPayment.class));
        assertThat(captor.getValue().getBody()).isEqualTo(payment);
    }
}
