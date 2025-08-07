package xyz.tcheeric.gateway.rest.repository;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@EntityScan("xyz.tcheeric.gateway.model.entity")
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldPersistAndFindPayment() {
        GatewayPayment payment = new GatewayPayment();
        payment.setPaymentId("payment123");
        payment.setQuoteId("quote456");
        payment.setRequest("request");
        payment.setState(State.PENDING);

        entityManager.persist(payment);
        entityManager.flush();

        Optional<GatewayPayment> byPaymentId = paymentRepository.findByPaymentId("payment123");
        Optional<GatewayPayment> byQuoteId = paymentRepository.findByQuoteId("quote456");

        assertThat(byPaymentId).isPresent();
        assertThat(byPaymentId.get().getQuoteId()).isEqualTo("quote456");

        assertThat(byQuoteId).isPresent();
        assertThat(byQuoteId.get().getPaymentId()).isEqualTo("payment123");
    }
}
