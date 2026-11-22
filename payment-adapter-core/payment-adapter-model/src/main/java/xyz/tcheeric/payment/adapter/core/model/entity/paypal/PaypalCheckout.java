package xyz.tcheeric.payment.adapter.core.model.entity.paypal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One PayPal order this service created for a purchase (V23).
 *
 * <p>Two jobs. It carries what a Stripe Checkout Session's metadata carries (the buyer key, the
 * deployment's name), because a PayPal order has only a 127-character {@code custom_id}: the
 * webhook reads the purchase's terms from here, never from anything that crossed PayPal. And it is
 * the reconciler's worklist: PayPal cannot list a seller's orders, so an order whose capture
 * webhook never arrived is found here.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "paypal_checkout", indexes = {
        @Index(name = "idx_paypal_checkout_status_created", columnList = "status, created_at")
})
public class PaypalCheckout {

    public enum Status {
        /** Created; the buyer has not paid. */
        CREATED,
        /** Captured: money moved and a purchase row exists or is being recorded. */
        CAPTURED,
        /** PayPal refused the capture, or the order expired or was voided. Terminal, nothing owed. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId;

    /** This service's id for the purchase, the response's {@code reference}. */
    @Column(name = "quote_id", nullable = false, unique = true, length = 64)
    private String quoteId;

    @Column(name = "stall_pubkey", nullable = false, length = 64)
    private String stallPubkey;

    @Column(name = "paypal_merchant_id", nullable = false, length = 64)
    private String paypalMerchantId;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    /** The session-metadata equivalent, as JSON: {@code buyer_pubkey}, {@code imani_env}. */
    @Column(name = "metadata", length = 4096)
    private String metadata;

    /** The deployment that created the order, or null when unnamed. */
    @Column(name = "environment", length = 64)
    private String environment;

    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "capture_id", unique = true, length = 64)
    private String captureId;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
