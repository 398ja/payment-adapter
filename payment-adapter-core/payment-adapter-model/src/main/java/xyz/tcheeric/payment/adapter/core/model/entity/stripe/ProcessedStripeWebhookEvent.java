package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

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

@Data
@NoArgsConstructor
@Entity
@Table(name = "processed_stripe_webhook_event", indexes = {
        @Index(name = "idx_processed_stripe_event_processed_at", columnList = "processed_at")
})
public class ProcessedStripeWebhookEvent {

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

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * When the current PROCESSING mark was taken. A mark older than any honest run is a crash
     * and may be taken over (card atomicity audit S5b, V15). Null on rows from before V15,
     * which are read as stale.
     */
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;
}
