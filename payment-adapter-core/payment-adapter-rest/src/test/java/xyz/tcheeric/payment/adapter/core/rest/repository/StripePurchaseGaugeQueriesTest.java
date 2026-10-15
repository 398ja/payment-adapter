package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paid-unissued gauge's two queries (imani-deploy#210, review of payment-adapter#268), against
 * a real database: the gauge is re-derived from these rows on every poll, so it survives a restart
 * and reads the same on every replica.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseGaugeQueriesTest {

    private static final Set<StripePurchase.Status> OPEN =
            EnumSet.complementOf(EnumSet.of(StripePurchase.Status.DISCHARGED));

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private TestEntityManager entityManager;

    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");

    private void persist(String eventId, StripePurchase.Status status, Instant createdAt) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(2500L);
        p.setCurrency("gbp");
        p.setStatus(status);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(createdAt);
        entityManager.persist(p);
        entityManager.flush();
    }

    @Test
    void countsEveryOpenRowAndNoDischargedOne() {
        persist("evt_old_done", StripePurchase.Status.DISCHARGED, now.minusSeconds(9_000));
        persist("evt_owed", StripePurchase.Status.OWED, now.minusSeconds(600));
        persist("evt_issuing", StripePurchase.Status.ISSUING, now.minusSeconds(300));
        persist("evt_issued", StripePurchase.Status.ISSUED, now.minusSeconds(1_200));
        persist("evt_blocked", StripePurchase.Status.BLOCKED, now.minusSeconds(60));

        assertThat(purchases.countByStatusIn(OPEN)).isEqualTo(4);
        assertThat(purchases.findOldestCreatedAtByStatusIn(OPEN)).isEqualTo(now.minusSeconds(1_200));
    }

    @Test
    void nothingOpenIsZeroAndNoOldest() {
        persist("evt_done", StripePurchase.Status.DISCHARGED, now.minusSeconds(60));

        assertThat(purchases.countByStatusIn(OPEN)).isZero();
        assertThat(purchases.findOldestCreatedAtByStatusIn(OPEN)).isNull();
    }
}
