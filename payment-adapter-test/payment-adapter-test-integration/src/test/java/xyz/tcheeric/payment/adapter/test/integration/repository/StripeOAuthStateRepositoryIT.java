package xyz.tcheeric.payment.adapter.test.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeOAuthState;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeOAuthStateRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

/**
 * The OAuth state must be spendable exactly once. Stripe revokes the connection if an authorization code is used
 * twice, so this is the guard that keeps a replayed or raced callback away from the code exchange.
 */
class StripeOAuthStateRepositoryIT extends BasePostgresIT {

    @Autowired
    private StripeOAuthStateRepository states;

    @BeforeEach
    void cleanUp() {
        states.deleteAll();
    }

    private StripeOAuthState state(String value, Instant expiresAt) {
        StripeOAuthState s = new StripeOAuthState();
        s.setState(value);
        s.setMerchantPubkey("npub_farm");
        s.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        s.setExpiresAt(expiresAt);
        return states.save(s);
    }

    // Verifies a fresh state is spent once and refused afterwards.
    @Test
    void consume_succeedsOnceThenRefuses() {
        state("st-once", Instant.now().plus(10, ChronoUnit.MINUTES));

        assertThat(states.consume("st-once", Instant.now())).isEqualTo(1);
        assertThat(states.consume("st-once", Instant.now())).isZero();
        assertThat(states.findById("st-once").orElseThrow().getConsumedAt()).isNotNull();
    }

    // Verifies an expired state cannot be spent.
    @Test
    void consume_refusesAnExpiredState() {
        state("st-old", Instant.now().minus(1, ChronoUnit.SECONDS));

        assertThat(states.consume("st-old", Instant.now())).isZero();
        assertThat(states.findById("st-old").orElseThrow().getConsumedAt()).isNull();
    }

    // Verifies a state that was never issued is refused, with no row created.
    @Test
    void consume_refusesAnUnknownState() {
        assertThat(states.consume("never-issued", Instant.now())).isZero();
        assertThat(states.findById("never-issued")).isEmpty();
    }

    // Verifies two callbacks racing on one state cannot both win: exactly one gets the code exchange.
    @Test
    void consume_hasExactlyOneWinnerUnderConcurrency() throws Exception {
        state("st-race", Instant.now().plus(10, ChronoUnit.MINUTES));
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Integer> call = () -> {
                    go.await();
                    return states.consume("st-race", Instant.now());
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            int winners = 0;
            for (Future<Integer> f : results) {
                winners += f.get();
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // Verifies housekeeping removes only rows that expired before the cutoff.
    @Test
    void deleteExpiredBefore_keepsLiveRows() {
        state("st-live", Instant.now().plus(10, ChronoUnit.MINUTES));
        state("st-dead", Instant.now().minus(2, ChronoUnit.HOURS));

        int removed = states.deleteExpiredBefore(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(removed).isEqualTo(1);
        assertThat(states.findById("st-live")).isPresent();
        assertThat(states.findById("st-dead")).isEmpty();
    }
}
