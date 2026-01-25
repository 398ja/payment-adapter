package xyz.tcheeric.payment.adapter.webhook.forwarder;

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
    private String quoteId;

    /**
     * Payment method (e.g., "bolt11", "cash").
     */
    private String paymentMethod;

    /**
     * Amount paid in minor units (satoshis for BTC).
     */
    private Integer amount;

    /**
     * Payment preimage (for Lightning payments).
     */
    private String preimage;

    /**
     * Receipt ID (for cash payments).
     */
    private String receiptId;

    /**
     * Timestamp when payment was confirmed.
     */
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
     */
    public static PaymentNotification forCash(String quoteId, Integer amount, String receiptId) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("cash")
                .amount(amount)
                .receiptId(receiptId)
                .paidAt(Instant.now())
                .build();
    }
}
