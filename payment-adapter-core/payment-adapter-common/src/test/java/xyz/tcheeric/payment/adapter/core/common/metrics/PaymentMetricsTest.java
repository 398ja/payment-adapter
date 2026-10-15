package xyz.tcheeric.payment.adapter.core.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;

class PaymentMetricsTest {

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
}
