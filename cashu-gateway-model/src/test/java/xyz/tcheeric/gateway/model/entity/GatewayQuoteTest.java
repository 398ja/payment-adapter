package xyz.tcheeric.gateway.model.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;

class GatewayQuoteTest {

    @Test
    void createInitializesDefaults() {
        GatewayQuote quote = GatewayQuote.create("qid", "iid", 60, "desc", "req", 100, "sat");
        assertThat(quote.getState()).isEqualTo(State.PENDING);
        assertThat(quote.getDirection()).isEqualTo(Direction.RECEIVE);
        assertThat(quote.getQuoteId()).isEqualTo("qid");
        assertThat(quote.getInvoiceId()).isEqualTo("iid");
    }
}
