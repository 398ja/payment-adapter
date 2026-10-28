package xyz.tcheeric.payment.adapter.core.model.entity.gifting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * What a gifting-site buyer reviewed, re-checked and pinned before any card form (imani-wallet#410 §3).
 *
 * <p>The delivery target is decided HERE, once, server-side (G-R1). A handle is resolved and its
 * pubkey stored; nothing after review, not a URL parameter, a second tab or the return URL, can
 * change who receives the voucher. One Checkout Session per draft (G-R6).
 *
 * <p>{@link #sealedSecret}, {@link #recipientEmail}, {@link #message} and the emails are excluded
 * from {@code toString} so a stray log line cannot carry them.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "purchase_draft")
public class PurchaseDraft {

    public static final String SELF = "self";
    public static final String HANDLE = "handle";
    public static final String EMAIL = "email";

    @Id
    private UUID id;

    @Column(name = "stall_pubkey", nullable = false, length = 64)
    private String stallPubkey;

    @Column(name = "stall_handle", length = 320)
    private String stallHandle;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "terms_version", length = 128)
    private String termsVersion;

    @Column(name = "delivery_kind", nullable = false, length = 16)
    private String deliveryKind;

    @Column(name = "recipient_pubkey", length = 64)
    private String recipientPubkey;

    @ToString.Exclude
    @Column(name = "recipient_email", length = 320)
    private String recipientEmail;

    @Column(name = "refund_pubkey", length = 64)
    private String refundPubkey;

    @ToString.Exclude
    @Column(name = "sealed_secret", length = 2048)
    private String sealedSecret;

    @ToString.Exclude
    @Column(name = "message", length = 280)
    private String message;

    @ToString.Exclude
    @Column(name = "sender_name", length = 80)
    private String senderName;

    @Column(name = "buyer_pubkey", length = 64)
    private String buyerPubkey;

    @ToString.Exclude
    @Column(name = "buyer_email", length = 320)
    private String buyerEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "checkout_session_id", unique = true)
    private String checkoutSessionId;

    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Two Pay clicks race on this: the loser re-reads and answers the winner's session. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
