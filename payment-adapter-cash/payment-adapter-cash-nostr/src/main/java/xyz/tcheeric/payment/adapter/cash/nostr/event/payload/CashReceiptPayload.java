package xyz.tcheeric.payment.adapter.cash.nostr.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted payload for CashReceipt event (kind 5202).
 * Sent by merchant to confirm cash received.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashReceiptPayload {

    /**
     * Invoice reference
     */
    @JsonProperty("ref")
    private String ref;

    /**
     * Status indicator (always "paid")
     */
    @JsonProperty("status")
    private String status;

    /**
     * Merchant's confirmation timestamp (Unix epoch seconds)
     */
    @JsonProperty("ts")
    private Long ts;

    /**
     * Actual amount received if different from invoice
     */
    @JsonProperty("amount_received")
    private Integer amountReceived;

    /**
     * Factory method for creating a paid receipt payload.
     */
    public static CashReceiptPayload paid(String ref, Integer amountReceived) {
        return CashReceiptPayload.builder()
                .ref(ref)
                .status("paid")
                .ts(System.currentTimeMillis() / 1000)
                .amountReceived(amountReceived)
                .build();
    }
}
