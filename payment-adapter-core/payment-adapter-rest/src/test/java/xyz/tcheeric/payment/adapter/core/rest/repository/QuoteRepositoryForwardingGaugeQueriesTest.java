package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two forwarding gauges' queries against real JPA (398ja/payment-adapter#259).
 *
 * <p>{@code payment_adapter_paid_unforwarded} is the "stuck right now, the sweep is still
 * trying" signal. {@code payment_adapter_forward_given_up} is the standing liability the sweep
 * has stopped retrying (#246). A row belongs to at most one of them, and a row that owes the mint
 * nothing ({@code amount <= 0}) belongs to neither. Before #259 the first counted given-up and
 * zero-amount rows forever, so its alert could never resolve.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class QuoteRepositoryForwardingGaugeQueriesTest {

    @Autowired
    private QuoteRepository quotes;

    @Autowired
    private TestEntityManager entityManager;

    private long baseUnforwarded;
    private long baseGivenUp;

    @BeforeEach
    void baseline() {
        baseUnforwarded = quotes.countPaidButNotForwarded();
        baseGivenUp = quotes.countForwardGivenUp();
    }

    private void persist(State state, Integer amount, Instant mintNotifiedAt, Instant gaveUpAt) {
        GatewayQuote q = new GatewayQuote();
        q.setQuoteId(UUID.randomUUID().toString());
        q.setInvoiceId(UUID.randomUUID().toString());
        q.setUnit("sat");
        q.setDirection(Direction.RECEIVE);
        q.setState(state);
        q.setAmount(amount);
        q.setMintNotifiedAt(mintNotifiedAt);
        q.setForwardGaveUpAt(gaveUpAt);
        entityManager.persist(q);
        entityManager.flush();
    }

    private long unforwarded() {
        return quotes.countPaidButNotForwarded() - baseUnforwarded;
    }

    private long givenUp() {
        return quotes.countForwardGivenUp() - baseGivenUp;
    }

    @Test
    @DisplayName("a positive-amount payment still being retried is paid-unforwarded, not given up")
    void retryingPaymentIsUnforwarded() {
        persist(State.PAID, 30, null, null);

        assertThat(unforwarded()).isEqualTo(1L);
        assertThat(givenUp()).isZero();
    }

    @Test
    @DisplayName("a given-up positive-amount payment moves to the given-up gauge and leaves paid-unforwarded")
    void givenUpPaymentMovesToTheLiabilityGauge() {
        persist(State.PAID, 30, null, Instant.now());

        assertThat(unforwarded())
                .as("the sweep has stopped on it, so it can never clear paid-unforwarded (#259)")
                .isZero();
        assertThat(givenUp())
                .as("still owed: the liability stays visible, on its own gauge (#246)")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a zero-amount PAID quote owes the mint nothing and is in neither gauge")
    void zeroAmountIsInNeitherGauge() {
        persist(State.PAID, 0, null, null);
        persist(State.PAID, 0, null, Instant.now());

        assertThat(unforwarded()).isZero();
        assertThat(givenUp()).isZero();
    }

    @Test
    @DisplayName("an unknown (null) amount is counted, because unknown is not the same as nothing owed")
    void nullAmountIsStillCounted() {
        persist(State.PAID, null, null, null);
        persist(State.PAID, null, null, Instant.now());

        assertThat(unforwarded()).isEqualTo(1L);
        assertThat(givenUp()).isEqualTo(1L);
    }

    @Test
    @DisplayName("delivered and unpaid quotes are in neither gauge")
    void deliveredAndUnpaidAreInNeither() {
        persist(State.PAID, 30, Instant.now(), null);
        persist(State.PENDING, 30, null, null);
        persist(State.CONFIRMED, 30, null, Instant.now());

        assertThat(unforwarded()).isZero();
        assertThat(givenUp()).isZero();
    }

    @Test
    @DisplayName("the sweep skips zero-amount rows (the mint refuses them) but keeps positive and null amounts")
    void sweepSkipsZeroAmountRows() {
        String zero = persistForSweep(0);
        String negative = persistForSweep(-5);
        String positive = persistForSweep(30);
        String unknown = persistForSweep(null);

        var ids = quotes.findPaidButNotForwarded(Instant.now().plusSeconds(60), Limit.of(1000)).stream()
                .map(GatewayQuote::getQuoteId)
                .toList();

        assertThat(ids)
                .as("a zero-amount forward is refused as invalid_amount every time, so retrying it is noise (#262 review L2)")
                .doesNotContain(zero, negative)
                .contains(positive, unknown);
    }

    private String persistForSweep(Integer amount) {
        GatewayQuote q = new GatewayQuote();
        q.setQuoteId(UUID.randomUUID().toString());
        q.setInvoiceId(UUID.randomUUID().toString());
        q.setUnit("sat");
        q.setDirection(Direction.RECEIVE);
        q.setState(State.PAID);
        q.setAmount(amount);
        q.setCreatedAt(Instant.now().minusSeconds(3600));
        entityManager.persist(q);
        entityManager.flush();
        return q.getQuoteId();
    }

    @Test
    @DisplayName("the staging #259 shape: nine zero-amount given-up rows read zero on both gauges")
    void stagingDebrisReadsZero() {
        for (int i = 0; i < 9; i++) {
            persist(State.PAID, 0, null, Instant.now());
        }
        persist(State.PAID, 21, null, null);

        assertThat(unforwarded())
                .as("only the real stranded payment remains, so the alert can resolve when it does")
                .isEqualTo(1L);
        assertThat(givenUp()).isZero();
    }
}
