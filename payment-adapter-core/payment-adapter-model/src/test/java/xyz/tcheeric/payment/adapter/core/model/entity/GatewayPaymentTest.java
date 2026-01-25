package xyz.tcheeric.payment.adapter.core.model.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

class GatewayPaymentTest {

    @Test
    void createInitializesDefaultState() {
        GatewayPayment payment = GatewayPayment.create(
                "req", "pid", "qid", "USD", 100, 1, 101, "hash", "pre");
        assertThat(payment.getState()).isEqualTo(State.PENDING);
        assertThat(payment.getQuoteId()).isEqualTo("qid");
        assertThat(payment.getPaymentId()).isEqualTo("pid");
    }
}
