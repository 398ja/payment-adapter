package xyz.tcheeric.gateway.rest.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@EntityScan("xyz.tcheeric.gateway.model.entity")
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void shouldPersistAndFindPayment() {
        GatewayPayment payment = new GatewayPayment();
        payment.setPaymentId("payment123");
        payment.setQuoteId("quote456");
        payment.setRequest("request");
        payment.setState(State.PENDING);

        paymentRepository.save(payment);

        GatewayPayment byPaymentId = paymentRepository.findByPaymentId("payment123");
        GatewayPayment byQuoteId = paymentRepository.findByQuoteId("quote456");

        assertThat(byPaymentId).isNotNull();
        assertThat(byPaymentId.getQuoteId()).isEqualTo("quote456");

        assertThat(byQuoteId).isNotNull();
        assertThat(byQuoteId.getPaymentId()).isEqualTo("payment123");
    }
}
