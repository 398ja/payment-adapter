package xyz.tcheeric.payment.adapter.core.rest;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scheduling must be enabled on the application itself.
 *
 * <p>Every safety net in this service is a {@code @Scheduled} method: the paid-unforwarded
 * gauge that reports stranded money, and the reconciler that re-delivers it. Without
 * {@code @EnableScheduling} on the application class none of them ever tick.
 *
 * <p>The failure that motivated this test is the quiet kind, and worth stating precisely: a
 * {@code @Scheduled} bean still constructs, still registers its gauge, and simply never
 * runs. So the gauge published a confident {@code 0.0} while the database held 88 stranded
 * payments. A missing series would have been noticed; a zero reads as "no money stranded"
 * and is strictly worse than publishing nothing at all.
 *
 * <p>It previously worked only by accident: {@code @EnableScheduling} sat on
 * {@code CashGatewayConfig}, an unrelated config in another module, so scheduling in this
 * application was a side effect of that bean happening to load.
 *
 * <p>This asserts the annotation rather than observing a tick, deliberately. A timing-based
 * test would be slow and flaky, and the thing that regressed was the declaration — someone
 * moving or removing it is exactly what this must catch.
 */
class PaymentGatewaySpringApplicationSchedulingTest {

    @Test
    void schedulingIsEnabledOnTheApplication() {
        EnableScheduling annotation =
                PaymentGatewaySpringApplication.class.getAnnotation(EnableScheduling.class);

        assertNotNull(annotation,
                "PaymentGatewaySpringApplication must declare @EnableScheduling: without it "
                        + "the paid-unforwarded gauge and the forward reconciler never tick, "
                        + "and the gauge reports a false zero rather than no value at all");
    }
}
