package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Card atomicity audit, scenario S1c (2026-10-15): one payment is at most one debt, whatever
 * events announce it, even when two replicas race past the listener's look-up.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseOneRowPerPaymentTest {

    @Autowired private TestEntityManager entityManager;

    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void aSecondRowForTheSameCheckoutSessionIsRefused() {
        persist("evt_completed", "cs_1", "pi_1");

        assertThatThrownBy(() -> persist("evt_async", "cs_1", null))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void aSecondRowForTheSamePaymentIntentIsRefused() {
        persist("evt_a", "cs_a", "pi_same");

        assertThatThrownBy(() -> persist("evt_b", "cs_b", "pi_same"))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void rowsWithoutAPaymentIntentDoNotCollide() {
        persist("evt_a", "cs_a", null);
        persist("evt_b", "cs_b", null);
    }

    private void persist(String eventId, String session, String paymentIntent) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId(session);
        p.setPaymentIntentId(paymentIntent);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(500L);
        p.setCurrency("gbp");
        p.setStatus(StripePurchase.Status.OWED);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        entityManager.persist(p);
        entityManager.flush();
    }
}
