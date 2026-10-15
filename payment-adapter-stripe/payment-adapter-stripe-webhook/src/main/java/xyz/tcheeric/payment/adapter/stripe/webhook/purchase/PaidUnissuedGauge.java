package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * Card purchases that were paid and have no confirmed coupon (imani-deploy#210).
 *
 * <p>The customer has paid and holds nothing: every status but {@code DISCHARGED} counts, an
 * {@code ISSUED} row included, because a coupon that never reached the buyer is not one they have.
 *
 * <p>Re-derived from SQL, like {@code PaidUnforwardedGauge}, because this is state that must
 * survive a restart, not a moment. Both series are bound eagerly, so the alert sees a healthy zero
 * rather than an absent series, and a failed poll holds the last value rather than reporting a
 * false zero that would clear a firing alert.
 */
@Slf4j
public class PaidUnissuedGauge {

    /** Names the alert rule reads. Renaming either silently disarms CardPaidNotIssued. */
    static final String COUNT = "imani_payments_paid_unissued";
    static final String OLDEST = "imani_payments_paid_unissued_oldest_seconds";

    private final StripePurchaseRepository purchases;
    private final Clock clock;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public PaidUnissuedGauge(StripePurchaseRepository purchases, MeterRegistry registry) {
        this(purchases, registry, Clock.systemUTC());
    }

    PaidUnissuedGauge(StripePurchaseRepository purchases, MeterRegistry registry, Clock clock) {
        this.purchases = purchases;
        this.clock = clock;
        if (registry != null) {
            Gauge.builder(COUNT, count, AtomicLong::get)
                    .description("Paid card purchases with no confirmed coupon. The customer has paid and holds nothing.")
                    .tag("source", "card")
                    .register(registry);
            Gauge.builder(OLDEST, oldestSeconds, AtomicLong::get)
                    .description("Age in seconds of the oldest paid card purchase with no confirmed coupon; 0 when none.")
                    .tag("source", "card")
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${purchase.unissued.gauge-interval:PT30S}")
    public void poll() {
        try {
            long n = purchases.countByStatusNot(StripePurchase.Status.DISCHARGED);
            Instant oldest = n == 0 ? null
                    : purchases.findOldestCreatedAtByStatusNot(StripePurchase.Status.DISCHARGED);
            count.set(n);
            oldestSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).getSeconds()));
        } catch (RuntimeException e) {
            log.warn("paid_unissued_poll_failed", e);
        }
    }

    long currentCount() {
        return count.get();
    }

    long currentOldestSeconds() {
        return oldestSeconds.get();
    }
}
