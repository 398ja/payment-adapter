package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * imani-wallet#96: the claim a purchase issuer must hold before it mints.
 *
 * <p>Against a real database, because the property under test is the
 * database's: a conditional UPDATE matches for exactly one caller.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseClaimTest {

    private static final StripePurchase.Status OWED = StripePurchase.Status.OWED;
    private static final StripePurchase.Status ISSUING = StripePurchase.Status.ISSUING;

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private TestEntityManager entityManager;

    private final Instant now = Instant.parse("2026-09-26T12:00:00Z");

    @BeforeEach
    void seed() {
        persist("evt_owed", OWED, null);
    }

    private void persist(String eventId, StripePurchase.Status status, Instant claimedUntil) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(2500L);
        p.setCurrency("gbp");
        p.setStatus(status);
        p.setClaimedUntil(claimedUntil);
        p.setCreatedAt(now.minusSeconds(60));
        p.setUpdatedAt(now.minusSeconds(60));
        entityManager.persist(p);
        entityManager.flush();
    }

    private int claim(String eventId, String worker, Instant at) {
        return purchases.claim(eventId, worker, at, at.plusSeconds(600), OWED, ISSUING);
    }

    @Test
    void exactlyOneOfTwoWorkersClaimsAnOwedRow() {
        assertThat(claim("evt_owed", "worker-a", now)).isEqualTo(1);
        assertThat(claim("evt_owed", "worker-b", now)).isZero();

        StripePurchase row = purchases.findByEventId("evt_owed").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ISSUING);
        assertThat(row.getClaimedBy()).isEqualTo("worker-a");
        assertThat(row.getClaimedUntil()).isEqualTo(now.plusSeconds(600));
    }

    @Test
    void aClaimedRowLeavesTheQueueUntilItsLeaseLapses() {
        claim("evt_owed", "worker-a", now);

        assertThat(purchases.findClaimable(OWED, ISSUING, now.plusSeconds(599))).isEmpty();
        assertThat(purchases.findClaimable(OWED, ISSUING, now.plusSeconds(601)))
                .extracting(StripePurchase::getEventId).containsExactly("evt_owed");
    }

    @Test
    void aLapsedClaimCanBeTakenOverByExactlyOneWorker() {
        claim("evt_owed", "dead-worker", now);

        Instant later = now.plusSeconds(601);
        assertThat(claim("evt_owed", "worker-b", later)).isEqualTo(1);
        assertThat(claim("evt_owed", "worker-c", later)).isZero();
        assertThat(purchases.findByEventId("evt_owed").orElseThrow().getClaimedBy()).isEqualTo("worker-b");
    }

    @Test
    void neverClaimsARowWhoseCouponAlreadyExistsOrIsClosed() {
        persist("evt_issued", StripePurchase.Status.ISSUED, null);
        persist("evt_done", StripePurchase.Status.DISCHARGED, null);
        persist("evt_blocked", StripePurchase.Status.BLOCKED, null);

        assertThat(claim("evt_issued", "w", now)).isZero();
        assertThat(claim("evt_done", "w", now)).isZero();
        assertThat(claim("evt_blocked", "w", now)).isZero();
        assertThat(purchases.findClaimable(OWED, ISSUING, now))
                .extracting(StripePurchase::getEventId).containsExactly("evt_owed");
    }
}
