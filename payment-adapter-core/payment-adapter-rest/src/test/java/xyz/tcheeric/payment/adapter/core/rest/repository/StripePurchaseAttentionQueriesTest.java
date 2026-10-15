package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment-adapter#273: NEEDS_ATTENTION rows, against a real database. The claim is the database's
 * decision, so "never hands out a NEEDS_ATTENTION row" and the lease-expiry count are tested here.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseAttentionQueriesTest {

    private static final StripePurchase.Status OWED = StripePurchase.Status.OWED;
    private static final StripePurchase.Status ISSUING = StripePurchase.Status.ISSUING;
    private static final StripePurchase.Status ATTENTION = StripePurchase.Status.NEEDS_ATTENTION;

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private TestEntityManager entityManager;

    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");

    private StripePurchase persist(String eventId, StripePurchase.Status status, AttentionReason reason) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(2500L);
        p.setCurrency("gbp");
        p.setStatus(status);
        p.setAttentionReason(reason);
        p.setCreatedAt(now.minusSeconds(60));
        p.setUpdatedAt(now.minusSeconds(60));
        entityManager.persist(p);
        entityManager.flush();
        return p;
    }

    private int claim(String eventId, Instant at) {
        return purchases.claim(eventId, "w", at, at.plusSeconds(600), OWED, ISSUING);
    }

    /** A row waiting for a person is never claimed and never listed as claimable. */
    @Test
    void neverClaimsOrListsANeedsAttentionRow() {
        persist("evt_owed", OWED, null);
        persist("evt_attention", ATTENTION, AttentionReason.GATEWAY_UNRECORDED);

        assertThat(claim("evt_attention", now)).isZero();
        assertThat(purchases.findClaimable(OWED, ISSUING, now))
                .extracting(StripePurchase::getEventId).containsExactly("evt_owed");
    }

    /** A first claim is not a lease expiry; taking over a lapsed lease is, once per takeover. */
    @Test
    void countsOnlyTakeoversOfALapsedLeaseAsExpiries() {
        persist("evt_owed", OWED, null);

        claim("evt_owed", now);
        assertThat(purchases.findByEventId("evt_owed").orElseThrow().getLeaseExpiries()).isZero();

        claim("evt_owed", now.plusSeconds(601));
        claim("evt_owed", now.plusSeconds(1202));
        assertThat(purchases.findByEventId("evt_owed").orElseThrow().getLeaseExpiries()).isEqualTo(2);
    }

    /** The gauge reads NEEDS_ATTENTION rows grouped by reason in one query. */
    @Test
    void countsAttentionRowsByReason() {
        persist("evt_a", ATTENTION, AttentionReason.GATEWAY_UNRECORDED);
        persist("evt_b", ATTENTION, AttentionReason.GATEWAY_UNRECORDED);
        persist("evt_c", ATTENTION, AttentionReason.STALE_ISSUING);
        persist("evt_owed", OWED, null);

        Map<AttentionReason, Long> counts = purchases.countByAttentionReason(ATTENTION).stream()
                .collect(Collectors.toMap(r -> (AttentionReason) r[0], r -> (Long) r[1]));

        assertThat(counts).containsOnly(
                Map.entry(AttentionReason.GATEWAY_UNRECORDED, 2L),
                Map.entry(AttentionReason.STALE_ISSUING, 1L));
    }

    /**
     * Refunds and disputes after issue are informational: counted while unacknowledged, and counted
     * again when a new reversal arrives after the acknowledgement.
     */
    @Test
    void countsUnacknowledgedReversalsAfterIssue() {
        StripePurchase refunded = persist("evt_refunded", StripePurchase.Status.DISCHARGED, null);
        refunded.setVoucherId("v1");
        refunded.setRefundedAmountMinor(500L);
        refunded.setReversedAt(now);
        StripePurchase disputed = persist("evt_disputed", StripePurchase.Status.ISSUED, null);
        disputed.setVoucherId("v2");
        disputed.setDisputed(true);
        disputed.setReversedAt(now);
        StripePurchase acknowledged = persist("evt_ack", StripePurchase.Status.DISCHARGED, null);
        acknowledged.setVoucherId("v3");
        acknowledged.setRefundedAmountMinor(500L);
        acknowledged.setReversedAt(now);
        acknowledged.setReversalAcknowledgedAt(now.plusSeconds(1));
        StripePurchase beforeIssue = persist("evt_pre", StripePurchase.Status.REFUNDED, null);
        beforeIssue.setRefundedAmountMinor(2500L);
        beforeIssue.setReversedAt(now);
        StripePurchase landedAfterRefund = persist("evt_landed", StripePurchase.Status.REFUNDED, null);
        landedAfterRefund.setVoucherId("v4");
        landedAfterRefund.setRefundedAmountMinor(2500L);
        landedAfterRefund.setReversedAt(now);
        entityManager.flush();

        assertThat(purchases.countUnacknowledgedRefundsAfterIssue())
                .as("a coupon that landed after the refund counts; a refund before any coupon does not")
                .isEqualTo(2);
        assertThat(purchases.countUnacknowledgedDisputes()).isEqualTo(1);

        acknowledged.setDisputed(true);
        acknowledged.setReversedAt(now.plusSeconds(2));
        entityManager.flush();
        assertThat(purchases.countUnacknowledgedDisputes()).isEqualTo(2);
    }

    /** The operator's list: attention rows, oldest first. */
    @Test
    void listsAttentionRowsOldestFirst() {
        persist("evt_a", ATTENTION, AttentionReason.DELIVERY_EXHAUSTED);
        persist("evt_owed", OWED, null);

        assertThat(purchases.findByStatusOrderByCreatedAtAsc(ATTENTION))
                .extracting(StripePurchase::getEventId).containsExactly("evt_a");
    }
}
