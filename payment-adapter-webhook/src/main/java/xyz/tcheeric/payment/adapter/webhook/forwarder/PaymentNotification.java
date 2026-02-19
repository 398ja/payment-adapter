package xyz.tcheeric.payment.adapter.webhook.forwarder;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Payment notification sent to cashu-mint when a payment is confirmed.
 * This DTO is used to forward payment confirmations from the webhook handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentNotification {

    /**
     * The quote identifier from the mint.
     */
    @JsonProperty("quote_id")
    private String quoteId;

    /**
     * Payment method (e.g., "bolt11", "cash").
     */
    @JsonProperty("payment_method")
    private String paymentMethod;

    /**
     * Amount paid in minor units (satoshis for BTC, cents for fiat).
     */
    private Integer amount;

    /**
     * Currency unit (e.g., "SAT", "USD", "KES"). Null defaults to SAT.
     */
    private String unit;

    /**
     * Payment preimage (for Lightning payments).
     */
    private String preimage;

    /**
     * Receipt ID (for cash payments).
     */
    @JsonProperty("receipt_id")
    private String receiptId;

    /**
     * Customer's Nostr public key hex (for cash payments, used for voucher delivery).
     */
    @JsonProperty("customer_pubkey")
    private String customerPubkey;

    /**
     * Timestamp when payment was confirmed.
     */
    @JsonProperty("paid_at")
    private Instant paidAt;

    /**
     * Get idempotency key for deduplication.
     */
    public String getIdempotencyKey() {
        return paymentMethod + ":" + quoteId;
    }

    /**
     * Create notification for Lightning (BOLT11) payment.
     */
    public static PaymentNotification forBolt11(String quoteId, Integer amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }

    /**
     * Create notification for cash payment.
     *
     * @param ref            invoice reference (used as quoteId)
     * @param amount         amount in minor currency units
     * @param unit           currency code (e.g., "KES", "USD", null for SAT)
     * @param receiptId      receipt entity ID
     * @param customerPubkey customer's Nostr pubkey hex (nullable)
     */
    public static PaymentNotification forCash(String ref, Integer amount, String unit,
                                               String receiptId, String customerPubkey) {
        return PaymentNotification.builder()
                .quoteId(ref)
                .paymentMethod("cash")
                .amount(amount)
                .unit(unit)
                .receiptId(receiptId)
                .customerPubkey(customerPubkey)
                .paidAt(Instant.now())
                .build();
    }
}
