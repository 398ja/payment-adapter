package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedStripeWebhookEventTest {

    // Verifies processed Stripe events carry a processing status and timestamps.
    @Test
    void keepsProcessingLifecycleFields() {
        Instant now = Instant.now();
        ProcessedStripeWebhookEvent event = new ProcessedStripeWebhookEvent();
        event.setEventId("evt_123");
        event.setEventType("checkout.session.completed");
        event.setPayloadHash("abc123");
        event.setLivemode(false);
        event.setReceivedAt(now);
        event.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        event.setProcessedAt(now);

        assertThat(event.getEventId()).isEqualTo("evt_123");
        assertThat(event.getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(now);
    }
}
