package xyz.tcheeric.payment.adapter.core.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.io.Serial;
import java.time.Instant;

/**
 * JPA entity representing a cash invoice for NIP-XX Cash Payments.
 * The merchant generates this invoice, publishes it to Nostr relays,
 * and displays it as a QR code for customers to scan.
 */
@Data
@Entity(name = "cash_invoice")
@Table(indexes = {
        @Index(name = "idx_cashinvoice_ref", columnList = "ref", unique = true),
        @Index(name = "idx_cashinvoice_ephemeral_pubkey", columnList = "ephemeral_pubkey"),
        @Index(name = "idx_cashinvoice_status", columnList = "status"),
        @Index(name = "idx_cashinvoice_expires_at", columnList = "expires_at")
})
@NoArgsConstructor
public class CashInvoice implements GatewayEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Unique invoice reference nonce (6-12 hex characters)
     */
    @Column(name = "ref", nullable = false, unique = true, length = 24)
    private String ref;

    /**
     * Merchant's ephemeral public key for this invoice (hex-encoded)
     */
    @Column(name = "ephemeral_pubkey", nullable = false, length = 66)
    private String ephemeralPubkey;

    /**
     * Merchant's ephemeral private key (hex-encoded, encrypted at rest)
     * Required for decrypting customer intents and signing receipts
     */
    @Column(name = "ephemeral_privkey", nullable = false, length = 64)
    private String ephemeralPrivkey;

    /**
     * Amount in minor currency units (cents for USD, satoshis for BTC)
     */
    @Column(name = "amount", nullable = false)
    private Integer amount;

    /**
     * ISO 4217 currency code (e.g., "USD", "EUR"). Null for satoshis.
     */
    @Column(name = "fiat", length = 3)
    private String fiat;

    /**
     * Optional memo/description (max 140 chars per spec)
     */
    @Column(name = "memo", length = 140)
    private String memo;

    /**
     * Proof code for counter verification (4-6 digits)
     */
    @Column(name = "proof_code", length = 6)
    private String proofCode;

    /**
     * Invoice expiry timestamp
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Comma-separated relay URLs
     */
    @Column(name = "relay_urls", nullable = false, length = 1024)
    private String relayUrls;

    /**
     * Current status of the invoice
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CashInvoiceStatus status;

    /**
     * Nostr event ID of the published invoice (kind 5200)
     */
    @Column(name = "event_id", length = 64)
    private String eventId;

    /**
     * Timestamp when the invoice was created
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Timestamp when the invoice was published to relays
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Timestamp when intent was received (kind 5201)
     */
    @Column(name = "intent_received_at")
    private Instant intentReceivedAt;

    /**
     * Timestamp when cash was confirmed received
     */
    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * Customer's Nostr public key (hex-encoded, set on intent received)
     */
    @Column(name = "customer_pubkey", length = 66)
    private String customerPubkey;

    /**
     * Cancellation reason code (if cancelled)
     */
    @Column(name = "cancel_reason", length = 64)
    private String cancelReason;

    /**
     * Factory method to create a new CashInvoice with default values.
     *
     * @param ref             unique invoice reference
     * @param ephemeralPubkey merchant's ephemeral public key
     * @param ephemeralPrivkey merchant's ephemeral private key
     * @param amount          amount in minor units
     * @param fiat            currency code (null for satoshis)
     * @param memo            optional description
     * @param expiresAt       expiry timestamp
     * @param relayUrls       comma-separated relay URLs
     * @return new CashInvoice with CREATED status
     */
    public static CashInvoice create(String ref, String ephemeralPubkey, String ephemeralPrivkey,
                                     Integer amount, String fiat, String memo,
                                     Instant expiresAt, String relayUrls) {
        CashInvoice invoice = new CashInvoice();
        invoice.setRef(ref);
        invoice.setEphemeralPubkey(ephemeralPubkey);
        invoice.setEphemeralPrivkey(ephemeralPrivkey);
        invoice.setAmount(amount);
        invoice.setFiat(fiat);
        invoice.setMemo(memo);
        invoice.setExpiresAt(expiresAt);
        invoice.setRelayUrls(relayUrls);
        invoice.setStatus(CashInvoiceStatus.CREATED);
        invoice.setCreatedAt(Instant.now());
        return invoice;
    }
}
