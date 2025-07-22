package xyz.tcheeric.gateway.rest.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@EntityScan("xyz.tcheeric.gateway.model.entity")
class RepositoryIntegrationTests {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private QuoteRepository quoteRepository;

    @Test
    void paymentCrudLifecycle() {
        GatewayPayment payment = new GatewayPayment();
        payment.setPaymentId("p123");
        payment.setQuoteId("q123");
        payment.setRequest("req");
        payment.setState(State.PENDING);

        paymentRepository.save(payment);

        GatewayPayment loaded = paymentRepository.findByPaymentId("p123");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getQuoteId()).isEqualTo("q123");
    }

    @Test
    void quoteCrudLifecycle() {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("q123");
        quote.setInvoiceId("inv123");
        quote.setRequest("req");
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);

        quoteRepository.save(quote);

        GatewayQuote loaded = quoteRepository.findByQuoteId("q123");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getInvoiceId()).isEqualTo("inv123");
    }
}
