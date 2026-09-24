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

    /**
     * The subset the sweep has stopped retrying (398ja/payment-adapter#246).
     *
     * <p>Separate from the total because the two want different responses. A rising
     * paid-unforwarded count may be a transient the sweep will clear by itself; a non-zero
     * given-up count never will, and nothing else in the system raises it again.
     */
    static final String GIVEN_UP_METRIC_NAME = "payment_adapter_forward_given_up";

    private final QuoteRepository quotes;
    private final AtomicLong paidUnforwarded = new AtomicLong();
    private final AtomicLong forwardGivenUp = new AtomicLong();

    public PaidUnforwardedGauge(@Autowired(required = false) QuoteRepository quotes,
                                @Autowired(required = false) MeterRegistry registry) {
        this.quotes = quotes;
        if (registry != null) {
            Gauge.builder(METRIC_NAME, paidUnforwarded, AtomicLong::get)
                    .description("Settled payments with no successful forward to the mint recorded. "
                            + "Non-zero means money was taken and the mint does not know, so it can "
                            + "neither issue against it nor see that it is missing.")
                    .register(registry);
            // Bound eagerly for the same reason as the gauge above: an alert on `> 0` cannot
            // tell a healthy zero from a series that does not exist yet.
            Gauge.builder(GIVEN_UP_METRIC_NAME, forwardGivenUp, AtomicLong::get)
                    .description("Settled payments the reconciler has stopped re-delivering after "
                            + "repeated refusals. The money is still owed and still counted by "
                            + METRIC_NAME + "; only the retry traffic has stopped. These will not "
                            + "resolve themselves.")
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
            forwardGivenUp.set(quotes.countForwardGivenUp());
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

    /** Last polled given-up count. */
    long currentGivenUp() {
        return forwardGivenUp.get();
    }
}
