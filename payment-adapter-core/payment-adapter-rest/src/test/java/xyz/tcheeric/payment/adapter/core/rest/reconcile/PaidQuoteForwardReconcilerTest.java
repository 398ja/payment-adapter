package xyz.tcheeric.payment.adapter.core.rest.reconcile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.reconcile.MintForwardRetrier;
import xyz.tcheeric.payment.adapter.core.rest.repository.QuoteRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the sweep rescues a payment the mint was never told about (398ja/cashu-mint#462).
 *
 * <p>Seven of these reached staging: settled at the adapter, never delivered to the mint, no row
 * on either side recording the gap. The direction matters — the money has already been taken, so
 * the sweep completes the obligation rather than abandoning it.
 */
class PaidQuoteForwardReconcilerTest {

    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final int BATCH = 100;

    private QuoteRepository quotes;
    private MintForwardRetrier retrier;
    private PaidQuoteForwardReconciler reconciler;

    @BeforeEach
    void setUp() {
        quotes = Mockito.mock(QuoteRepository.class);
        retrier = Mockito.mock(MintForwardRetrier.class);
        reconciler = new PaidQuoteForwardReconciler(quotes, retrier, true, GRACE, BATCH);
    }

    private static GatewayQuote paidQuote(String quoteId) {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(quoteId);
        quote.setAmount(30);
        quote.setUnit("sat");
        quote.setState(State.PAID);
        quote.setCreatedAt(Instant.now().minus(Duration.ofHours(1)));
        return quote;
    }

    private void sweepFinding(GatewayQuote... stranded) {
        when(quotes.findPaidButNotForwarded(any(), any())).thenReturn(List.of(stranded));
        reconciler.reconcileTick();
    }

    /** The case this exists for: money taken, mint never told, nobody else coming. */
    @Test
    @DisplayName("re-delivers a settled payment the mint never received")
    void redeliversAnUnforwardedPayment() {
        GatewayQuote stranded = paidQuote("e32c04c5-10ee-4ee9-aab0-d6bfccc3812d");
        when(retrier.retryForward(stranded)).thenReturn(true);

        sweepFinding(stranded);

        verify(retrier).retryForward(stranded);
        assertThat(stranded.getMintNotifiedAt())
                .as("a delivered payment must be stamped, or the sweep re-delivers it forever")
                .isNotNull();
        verify(quotes).save(stranded);
    }

    /**
     * All seven heal on one sweep rather than one per tick, which is what makes the existing
     * backlog recoverable without an operator walking it.
     */
    @Test
    @DisplayName("re-delivers every stranded payment in one sweep")
    void redeliversEveryStrandedPaymentInOneSweep() {
        GatewayQuote[] stranded = new GatewayQuote[7];
        for (int i = 0; i < stranded.length; i++) {
            stranded[i] = paidQuote("stranded-" + i);
            when(retrier.retryForward(stranded[i])).thenReturn(true);
        }

        sweepFinding(stranded);

        for (GatewayQuote quote : stranded) {
            verify(retrier).retryForward(quote);
            assertThat(quote.getMintNotifiedAt()).isNotNull();
        }
    }

    /**
     * A payment that settled moments ago has its inline forward still in flight. Without the
     * grace period the sweep would race the code path it exists to back up, and re-deliver
     * every healthy payment in the system.
     */
    @Test
    @DisplayName("asks only for payments older than the grace period")
    void onlyConsidersPaymentsOlderThanTheGracePeriod() {
        Instant boundary = Instant.now().minus(GRACE);
        when(quotes.findPaidButNotForwarded(any(), any())).thenReturn(List.of());

        reconciler.reconcileTick();

        verify(quotes).findPaidButNotForwarded(
                Mockito.argThat(cutoff -> !cutoff.isBefore(boundary.minusSeconds(5))
                        && !cutoff.isAfter(Instant.now())),
                Mockito.eq(Limit.of(BATCH)));
    }

    /**
     * A payment the mint still will not accept must stay visible rather than be marked done.
     * Stamping it would make the money invisible again, which is the defect being fixed.
     */
    @Test
    @DisplayName("leaves an undeliverable payment unstamped and visible")
    void leavesAnUndeliverablePaymentVisible() {
        GatewayQuote stranded = paidQuote("still-refused");
        when(retrier.retryForward(stranded)).thenReturn(false);

        sweepFinding(stranded);

        assertThat(stranded.getMintNotifiedAt())
                .as("an undelivered payment must remain countable by the gauge")
                .isNull();
        verify(quotes, never()).save(any());
    }

    /** One unrecoverable payment must not stop the others being swept. */
    @Test
    @DisplayName("keeps sweeping after one payment fails")
    void keepsSweepingAfterOneFails() {
        GatewayQuote failing = paidQuote("throws");
        GatewayQuote healthy = paidQuote("recovers");
        when(retrier.retryForward(failing)).thenThrow(new RuntimeException("mint unreachable"));
        when(retrier.retryForward(healthy)).thenReturn(true);

        sweepFinding(failing, healthy);

        verify(retrier).retryForward(healthy);
        assertThat(healthy.getMintNotifiedAt()).isNotNull();
    }

    /** A failing query must not kill the schedule: the next sweep is the only recovery. */
    @Test
    @DisplayName("survives a tick whose query fails")
    void survivesAFailingQuery() {
        when(quotes.findPaidButNotForwarded(any(), any()))
                .thenThrow(new RuntimeException("database unreachable"));

        reconciler.reconcileTick();

        verify(quotes, never()).save(any());
    }

    /**
     * Off by default. The seven known stranded payments are weeks old, and re-delivering them
     * mints value against payments taken long ago — an operator decision, not a side effect of
     * deploying the mechanism.
     */
    @Test
    @DisplayName("does nothing until explicitly enabled")
    void doesNothingWhenDisabled() {
        PaidQuoteForwardReconciler disabled =
                new PaidQuoteForwardReconciler(quotes, retrier, false, GRACE, BATCH);

        disabled.reconcileTick();

        verify(quotes, never()).findPaidButNotForwarded(any(), any());
    }

    /**
     * A batch size of zero would make the sweep a silent no-op — the one failure a safety net
     * must not have, because it looks exactly like a healthy system with nothing to do.
     */
    @Test
    @DisplayName("refuses a non-positive batch size rather than sweeping nothing")
    void refusesANonPositiveBatchSize() {
        PaidQuoteForwardReconciler misconfigured =
                new PaidQuoteForwardReconciler(quotes, retrier, true, GRACE, 0);
        when(quotes.findPaidButNotForwarded(any(), any())).thenReturn(List.of());

        misconfigured.reconcileTick();

        verify(quotes).findPaidButNotForwarded(any(),
                Mockito.argThat(limit -> limit.max() > 0));
    }

    /** Without its dependencies the sweep does nothing rather than failing on every tick. */
    @Test
    @DisplayName("does nothing when the dependencies are not wired")
    void doesNothingWhenDependenciesAreMissing() {
        PaidQuoteForwardReconciler unwired =
                new PaidQuoteForwardReconciler(null, null, true, GRACE, BATCH);

        unwired.reconcileTick();

        verify(quotes, never()).findPaidButNotForwarded(any(), any());
    }
}
