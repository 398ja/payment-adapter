package xyz.tcheeric.payment.adapter.core.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import java.time.Duration;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;

class PaymentMetricsTest {

    /** #272: the reconciled counter exists at zero before anything is reconciled, and counts by source. */
    @Test
    void theReconciledCounterIsPreRegisteredAndCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMetrics metrics = new PaymentMetrics(registry).preRegisterReconciled(Source.CARD);
        assertEquals(0.0, registry.get(PaymentMetrics.RECONCILED).tag("source", "card").counter().count());

        metrics.reconciled(Source.CARD);

        assertEquals(1.0, registry.get(PaymentMetrics.RECONCILED).tag("source", "card").counter().count());
    }

    /** #268 "metrics never throw": a closed registry does not break the reconciler. */
    @Test
    void reconcilingNeverThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.close();
        new PaymentMetrics(registry).preRegisterReconciled(Source.CARD).reconciled(Source.CARD);
    }

    @Test
    void labelsAreTheFixedSetAndNothingElse() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMetrics(registry).record(Source.CARD, Status.SUCCEEDED, Reason.NONE, "gbp", 2500L);

        for (Meter meter : registry.getMeters()) {
            Set<String> keys = meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
            if (meter.getId().getName().equals(PaymentMetrics.PAYMENTS)) {
                assertEquals(Set.of("source", "status", "reason", "currency"), keys);
            } else {
                assertEquals(Set.of("source", "status", "currency"), keys);
            }
        }
        assertEquals(1.0, registry.get(PaymentMetrics.PAYMENTS).tag("currency", "GBP").tag("status", "succeeded")
                .counter().count());
        assertEquals(2500.0, registry.get(PaymentMetrics.AMOUNT).tag("currency", "GBP").counter().count());
    }

    @Test
    void anythingThatIsNotAnIsoCodeCollapsesToNone() {
        // A currency is the only label a caller supplies. A pubkey, a session id or an amount
        // passed by mistake must not become a series of its own.
        assertEquals("NONE", PaymentMetrics.currency("cs_test_123"));
        assertEquals("NONE", PaymentMetrics.currency("a".repeat(64)));
        assertEquals("NONE", PaymentMetrics.currency("2500"));
        assertEquals("NONE", PaymentMetrics.currency(null));
        assertEquals("XAF", PaymentMetrics.currency(" xaf "));
        assertEquals("SAT", PaymentMetrics.currency("sat"));
    }

    @Test
    void preRegisteredSeriesExistAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMetrics(registry).preRegister(Source.CASH, Status.SUCCEEDED).preRegisterWebhooks(Source.CARD);

        assertEquals(0.0, registry.get(PaymentMetrics.PAYMENTS).tag("source", "cash").counter().count());
        assertNotNull(registry.find(PaymentMetrics.WEBHOOKS).tag("outcome", "signature_rejected").counter());
        assertTrue(registry.find(PaymentMetrics.WEBHOOKS).counters().size() >= 6);
    }

    /** Review of gateway-core#161, S-4, applied to PA: a broken registry never throws into a payment path. */
    @Test
    void aBrokenRegistryNeverThrowsIntoTheCaller() {
        SimpleMeterRegistry broken = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry down");
            }

            @Override
            protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector pause) {
                throw new IllegalStateException("registry down");
            }
        };
        PaymentMetrics metrics = new PaymentMetrics(broken)
                .preRegister(Source.CARD, Status.values())
                .preRegisterWebhooks(Source.CARD)
                .preRegisterConnect();

        metrics.record(Source.CARD, Status.SUCCEEDED, Reason.NONE, "gbp", 2500L);
        metrics.webhook(Source.CARD, PaymentMetrics.WebhookOutcome.FAILED);
        metrics.connect(PaymentMetrics.ConnectEvent.ONBOARDING_STARTED);
        metrics.duration(Source.CARD, PaymentMetrics.Stage.WEBHOOK, Duration.ofMillis(5));
    }
}
