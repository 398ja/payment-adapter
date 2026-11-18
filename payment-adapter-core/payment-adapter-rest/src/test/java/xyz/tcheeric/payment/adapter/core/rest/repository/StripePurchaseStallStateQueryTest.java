package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** imani-wallet#277: a stall's refund-state read lists only its own purchases, newest first. */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseStallStateQueryTest {

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private TestEntityManager entityManager;

    private void persist(String eventId, String stall, Instant createdAt, StripePurchase.DisputeStatus dispute) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentIntentId("pi_" + eventId);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setStallPubkey(stall);
        p.setAmountMinor(1000L);
        p.setCurrency("gbp");
        p.setStatus(StripePurchase.Status.DISCHARGED);
        p.setDisputeStatus(dispute);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(createdAt);
        entityManager.persist(p);
        entityManager.flush();
    }

    @Test
    void listsOneStallsPurchasesNewestFirst() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        persist("e1", a, Instant.parse("2026-11-01T00:00:00Z"), null);
        persist("e2", b, Instant.parse("2026-11-02T00:00:00Z"), null);
        persist("e3", a, Instant.parse("2026-11-03T00:00:00Z"), StripePurchase.DisputeStatus.WON);

        var rows = purchases.findTop200ByStallPubkeyOrderByCreatedAtDesc(a);

        assertThat(rows).extracting(StripePurchase::getEventId).containsExactly("e3", "e1");
        assertThat(rows.get(0).getDisputeStatus()).isEqualTo(StripePurchase.DisputeStatus.WON);
    }

    @Test
    void theSalesListHasOnlyPurchasesThatMintedACoupon() {
        String a = "c".repeat(64);
        persist("m1", a, Instant.parse("2026-11-01T00:00:00Z"), null);
        persist("m2", a, Instant.parse("2026-11-02T00:00:00Z"), null);
        StripePurchase minted = purchases.findByEventId("m2").orElseThrow();
        minted.setVoucherId("v-2");
        purchases.save(minted);
        entityManager.flush();

        var rows = purchases.findTop200ByStallPubkeyAndVoucherIdIsNotNullOrderByCreatedAtDesc(a);

        assertThat(rows).extracting(StripePurchase::getEventId).containsExactly("m2");
    }
}
