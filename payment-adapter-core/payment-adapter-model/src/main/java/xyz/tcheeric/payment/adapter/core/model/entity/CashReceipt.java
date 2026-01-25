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
 * JPA entity representing a cash receipt confirmation (NIP-XX kind 5202).
 * The merchant publishes this after receiving physical cash from the customer.
 */
@Data
@Entity(name = "cash_receipt")
@Table(indexes = {
        @Index(name = "idx_cashreceipt_ref", columnList = "ref", unique = true),
        @Index(name = "idx_cashreceipt_event_id", columnList = "event_id", unique = true)
})
@NoArgsConstructor
public class CashReceipt implements GatewayEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Invoice reference this receipt is for
     */
    @Column(name = "ref", nullable = false, unique = true, length = 24)
    private String ref;

    /**
     * Actual amount received (may differ from invoice amount)
     */
    @Column(name = "amount_received", nullable = false)
    private Integer amountReceived;

    /**
     * Timestamp when cash was confirmed received
     */
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    /**
     * Nostr event ID of the receipt (kind 5202)
     */
    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    /**
     * Timestamp when the receipt was published
     */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /**
     * Factory method to create a new CashReceipt.
     *
     * @param ref            invoice reference
     * @param amountReceived actual amount received
     * @param eventId        Nostr event ID of the published receipt
     * @return new CashReceipt
     */
    public static CashReceipt create(String ref, Integer amountReceived, String eventId) {
        CashReceipt receipt = new CashReceipt();
        receipt.setRef(ref);
        receipt.setAmountReceived(amountReceived);
        receipt.setConfirmedAt(Instant.now());
        receipt.setEventId(eventId);
        receipt.setPublishedAt(Instant.now());
        return receipt;
    }
}
