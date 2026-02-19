package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;

import java.time.Instant;

/**
 * Response DTO for cash receipt confirmation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashReceiptResponse {

    /**
     * Invoice reference.
     */
    private String ref;

    /**
     * Amount received in minor currency units.
     */
    private Integer amountReceived;

    /**
     * Nostr event ID for the receipt.
     */
    private String eventId;

    /**
     * Time when receipt was confirmed.
     */
    private Instant confirmedAt;

    /**
     * Create response from CashReceipt entity.
     */
    public static CashReceiptResponse fromEntity(CashReceipt receipt) {
        return CashReceiptResponse.builder()
                .ref(receipt.getRef())
                .amountReceived(receipt.getAmountReceived())
                .eventId(receipt.getEventId())
                .confirmedAt(receipt.getConfirmedAt())
                .build();
    }
}
