package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review of #278: every writer of a purchase saves the whole row. A discharge that read the row
 * before a refund was recorded (it spends seconds asking the gateway) would write its stale copy
 * back over the refund, which Stripe was already told is done. A version makes the late writer
 * fail instead, so it re-reads (the issuer reports again; Stripe redelivers).
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseConcurrentWriteTest {

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private TestEntityManager entityManager;

    @Test
    void aStaleWriterCannotEraseARecordedRefund() {
        StripePurchase p = new StripePurchase();
        p.setEventId("evt_race");
        p.setCheckoutSessionId("cs_race");
        p.setPaymentIntentId("pi_race");
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(500L);
        p.setCurrency("gbp");
        p.setStatus(StripePurchase.Status.ISSUING);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        entityManager.persistAndFlush(p);

        // The discharge's copy, read before the refund landed.
        StripePurchase stale = purchases.findByEventId("evt_race").orElseThrow();
        entityManager.detach(stale);

        // The refund is recorded and committed meanwhile.
        StripePurchase fresh = purchases.findByEventId("evt_race").orElseThrow();
        fresh.setStatus(StripePurchase.Status.REFUNDED);
        fresh.setRefundedAmountMinor(500L);
        entityManager.flush();
        entityManager.clear();

        // The discharge finishes and writes its copy.
        stale.setStatus(StripePurchase.Status.DISCHARGED);
        stale.setVoucherId("v1");
        assertThatThrownBy(() -> {
            purchases.save(stale);
            entityManager.flush();
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        entityManager.clear();
        StripePurchase row = purchases.findByEventId("evt_race").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(StripePurchase.Status.REFUNDED);
        assertThat(row.getRefundedAmountMinor()).isEqualTo(500L);
    }
}
