package xyz.tcheeric.payment.adapter.core.rest.reconcile;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.payment.adapter.core.rest.repository.QuoteRepository;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports the count of settled payments the mint was never told about
 * (398ja/cashu-mint#462).
 *
 * <p>A gauge re-derived from SQL rather than a counter incremented at the failure, following the
 * same reasoning as cashu-mint's ADR 0002: this is a <em>duration in database state</em>, not an
 * in-process moment. A counter could express neither "stranded for three weeks" nor survive a
 * restart with its standing count intact, which is precisely wrong for a condition that by design
 * never resolves itself.
 *
 * <p><strong>Why this cannot live in the mint.</strong> cashu-mint has its own paid-unfunded
 * gauge, but it counts voucher quotes that <em>have</em> an accepted webhook event. A payment
 * that never arrived is invisible to it — the mint cannot report money taken somewhere else that
 * it was never told about. Only this side knows the payment happened at all, which is why the
 * gauge belongs here and why the mint's green dashboard was not evidence of anything.
 *
 * <p>The series is bound eagerly so it is scrapeable at zero before anything strands. An alert on
 * {@code > 0} cannot distinguish a healthy zero from a missing series, so a gauge that only
 * materialises once money is lost is an alert that never arms.
 */
@Slf4j
@Component
public class PaidUnforwardedGauge {

    /** Name the alert rule reads. Changing it silently disarms the alert. */
    static final String METRIC_NAME = "payment_adapter_paid_unforwarded";

    private final QuoteRepository quotes;
    private final AtomicLong paidUnforwarded = new AtomicLong();

    public PaidUnforwardedGauge(@Autowired(required = false) QuoteRepository quotes,
                                @Autowired(required = false) MeterRegistry registry) {
        this.quotes = quotes;
        if (registry != null) {
            Gauge.builder(METRIC_NAME, paidUnforwarded, AtomicLong::get)
                    .description("Settled payments with no successful forward to the mint recorded. "
                            + "Non-zero means money was taken and the mint does not know, so it can "
                            + "neither issue against it nor see that it is missing.")
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${mint.webhook.reconcile.gauge-interval:PT60S}")
    public void pollTick() {
        if (quotes == null) {
            return;
        }
        try {
            paidUnforwarded.set(quotes.countPaidButNotForwarded());
        } catch (RuntimeException e) {
            // Hold the last known value rather than reporting a false zero: a zero here would
            // silently clear a firing alert, which is the one failure mode worse than the
            // condition being alerted on.
            log.warn("paid_unforwarded_poll_failed", e);
        }
    }

    /** Last polled value, for tests and for anything that wants it without a scrape. */
    long current() {
        return paidUnforwarded.get();
    }
}
