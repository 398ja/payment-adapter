package xyz.tcheeric.payment.adapter.core.model.entity.paypal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;

/**
 * A PayPal webhook event's processing mark: {@code ProcessedStripeWebhookEvent}'s lifecycle, for
 * PayPal's event ids (V23). Shares the status enum, because the states mean the same thing.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "processed_paypal_webhook_event", indexes = {
        @Index(name = "idx_processed_paypal_event_processed_at", columnList = "processed_at")
})
public class ProcessedPaypalWebhookEvent {

    @Id
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private StripeWebhookProcessingStatus processingStatus;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;
}
