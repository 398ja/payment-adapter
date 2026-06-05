package xyz.tcheeric.payment.adapter.core.model.entity;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

class GatewayQuoteTest {

    @Test
    void createInitializesDefaults() {
        GatewayQuote quote = GatewayQuote.create("qid", "iid", 60, "desc", "req", 100, "sat");
        assertThat(quote.getState()).isEqualTo(State.PENDING);
        assertThat(quote.getDirection()).isEqualTo(Direction.RECEIVE);
        assertThat(quote.getQuoteId()).isEqualTo("qid");
        assertThat(quote.getInvoiceId()).isEqualTo("iid");
    }

    @Test
    void prePersistPopulatesCreatedAtWhenNull() {
        // Spec 041 REQ-MINT-3 — the mint reads Gateway.getCreatedAt to compute
        // strict quote expiry. The @PrePersist hook MUST populate createdAt
        // whenever JPA persists a NEW row.
        GatewayQuote quote = GatewayQuote.create("qid", "iid", 60, "desc", "req", 100, "sat");
        assertThat(quote.getCreatedAt()).isNull();

        Instant before = Instant.now();
        quote.onCreate();
        Instant after = Instant.now();

        assertThat(quote.getCreatedAt())
                .isNotNull()
                .isBetween(before, after);
    }

    @Test
    void prePersistDoesNotOverwriteAnExplicitlySetCreatedAt() {
        // Backward compatibility — if a caller pre-populates createdAt
        // (e.g. during a backfill of legacy rows), the @PrePersist hook
        // MUST NOT overwrite it.
        GatewayQuote quote = GatewayQuote.create("qid", "iid", 60, "desc", "req", 100, "sat");
        Instant explicit = Instant.parse("2025-01-15T10:30:00Z");
        quote.setCreatedAt(explicit);

        quote.onCreate();

        assertThat(quote.getCreatedAt()).isEqualTo(explicit);
    }
}
