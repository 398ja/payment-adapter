package xyz.tcheeric.payment.adapter.core.rest.reconcile;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.payment.adapter.core.rest.repository.QuoteRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The gauge behind the only alert that can see money taken here and not known to the mint
 * (398ja/cashu-mint#462).
 *
 * <p>Each test asserts the <em>exported</em> value rather than the repository count. Asserting
 * the count would pass whether or not the poll is wired to the meter — the same blind spot that
 * let both of cashu-mint's invariant gauges sit bound-but-never-polled, reading a healthy zero
 * forever while their tests stayed green.
 */
class PaidUnforwardedGaugeTest {

    private final QuoteRepository quotes = Mockito.mock(QuoteRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private PaidUnforwardedGauge gauge() {
        return new PaidUnforwardedGauge(quotes, registry);
    }

    private Double exported() {
        var meter = registry.find(PaidUnforwardedGauge.METRIC_NAME).gauge();
        return meter == null ? null : meter.value();
    }

    /**
     * The series must exist at zero before anything strands. An alert on {@code > 0} cannot tell
     * a healthy zero from a missing series, so a gauge that appears only once money is lost is an
     * alert that never arms.
     */
    @Test
    @DisplayName("the series is scrapeable at zero before anything is stranded")
    void isRegisteredAtZeroBeforeAnythingStrands() {
        gauge();

        assertThat(exported())
                .as("absent and zero are indistinguishable to the alert rule")
                .isEqualTo(0.0);
    }

    /** The defect made visible: money taken, mint not told. */
    @Test
    @DisplayName("exports the number of settled payments the mint never received")
    void exportsTheStrandedCount() {
        when(quotes.countPaidButNotForwarded()).thenReturn(7L);

        PaidUnforwardedGauge gauge = gauge();
        gauge.pollTick();

        assertThat(exported())
                .as("the stranded count must reach the scrape, or the alert never fires")
                .isEqualTo(7.0);
    }

    /** And it must come back down, or the alert pages forever and gets muted. */
    @Test
    @DisplayName("returns to zero once the payments are delivered")
    void clearsOnceDelivered() {
        when(quotes.countPaidButNotForwarded()).thenReturn(7L, 0L);
        PaidUnforwardedGauge gauge = gauge();

        gauge.pollTick();
        assertThat(exported()).isEqualTo(7.0);

        gauge.pollTick();
        assertThat(exported())
                .as("an alert that cannot clear is one nobody reads twice")
                .isZero();
    }

    /**
     * A failing query must hold the last value, not report zero. Reporting zero would silently
     * clear a firing alert — worse than the condition it was reporting.
     */
    @Test
    @DisplayName("holds the last value when the query fails rather than reporting a false zero")
    void holdsLastValueOnQueryFailure() {
        when(quotes.countPaidButNotForwarded())
                .thenReturn(7L)
                .thenThrow(new RuntimeException("database unreachable"));
        PaidUnforwardedGauge gauge = gauge();

        gauge.pollTick();
        gauge.pollTick();

        assertThat(exported())
                .as("a false zero would clear the alert while the money is still missing")
                .isEqualTo(7.0);
    }

    /** Without a repository the poll does nothing rather than failing on every tick. */
    @Test
    @DisplayName("does nothing when the repository is not wired")
    void doesNothingWhenUnwired() {
        PaidUnforwardedGauge unwired = new PaidUnforwardedGauge(null, registry);

        unwired.pollTick();

        assertThat(exported()).isEqualTo(0.0);
    }
}
