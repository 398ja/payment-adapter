package xyz.tcheeric.payment.adapter.core.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.Instant;

/**
 * JPA entity representing a customer's intent to pay cash (NIP-XX kind 5201).
 * This is received when a customer scans a merchant's QR code and signals
 * their intent to complete the cash transaction.
 */
@Data
@Entity(name = "cash_intent")
@Table(indexes = {
        @Index(name = "idx_cashintent_ref", columnList = "ref"),
        @Index(name = "idx_cashintent_customer_pubkey", columnList = "customer_pubkey"),
        @Index(name = "idx_cashintent_event_id", columnList = "event_id", unique = true)
})
@NoArgsConstructor
public class CashIntent implements GatewayEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Invoice reference this intent is for
     */
    @Column(name = "ref", nullable = false, length = 24)
    private String ref;

    /**
     * Customer's ephemeral public key (hex-encoded)
     */
    @Column(name = "customer_pubkey", nullable = false, length = 66)
    private String customerPubkey;

    /**
     * Optional proof code provided by customer for verification
     */
    @Column(name = "proof", length = 6)
    private String proof;

    /**
     * Customer's timestamp from the intent event
     */
    @Column(name = "customer_timestamp")
    private Instant customerTimestamp;

    /**
     * Nostr event ID of the intent (kind 5201)
     */
    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    /**
     * Timestamp when the intent was received
     */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /**
     * Whether this intent has been processed (to prevent duplicates)
     */
    @Column(name = "processed", nullable = false)
    private boolean processed;

    /**
     * Factory method to create a new CashIntent.
     *
     * @param ref               invoice reference
     * @param customerPubkey    customer's ephemeral public key
     * @param proof             optional proof code
     * @param customerTimestamp customer's timestamp
     * @param eventId           Nostr event ID
     * @return new CashIntent
     */
    public static CashIntent create(String ref, String customerPubkey, String proof,
                                    Instant customerTimestamp, String eventId) {
        CashIntent intent = new CashIntent();
        intent.setRef(ref);
        intent.setCustomerPubkey(customerPubkey);
        intent.setProof(proof);
        intent.setCustomerTimestamp(customerTimestamp);
        intent.setEventId(eventId);
        intent.setReceivedAt(Instant.now());
        intent.setProcessed(false);
        return intent;
    }
}
