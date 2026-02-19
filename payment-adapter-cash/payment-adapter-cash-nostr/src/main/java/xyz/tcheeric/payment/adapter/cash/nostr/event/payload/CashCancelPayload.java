package xyz.tcheeric.payment.adapter.cash.nostr.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted payload for CashCancel event (kind 5203).
 * Used for cancellation, timeout, or decline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashCancelPayload {

    /**
     * Invoice reference
     */
    @JsonProperty("ref")
    private String ref;

    /**
     * Status indicator ("cancelled" or "expired")
     */
    @JsonProperty("status")
    private String status;

    /**
     * Machine-readable reason code
     */
    @JsonProperty("reason")
    private String reason;

    /**
     * Cancellation timestamp (Unix epoch seconds)
     */
    @JsonProperty("ts")
    private Long ts;

    /**
     * Factory method for expired invoice.
     */
    public static CashCancelPayload expired(String ref) {
        return CashCancelPayload.builder()
                .ref(ref)
                .status("expired")
                .reason("cash.expired")
                .ts(System.currentTimeMillis() / 1000)
                .build();
    }

    /**
     * Factory method for merchant cancellation.
     */
    public static CashCancelPayload cancelledByMerchant(String ref, String reason) {
        return CashCancelPayload.builder()
                .ref(ref)
                .status("cancelled")
                .reason(reason != null ? reason : "cash.cancelled_by_merchant")
                .ts(System.currentTimeMillis() / 1000)
                .build();
    }

    /**
     * Factory method for customer cancellation.
     */
    public static CashCancelPayload cancelledByCustomer(String ref) {
        return CashCancelPayload.builder()
                .ref(ref)
                .status("cancelled")
                .reason("cash.cancelled_by_customer")
                .ts(System.currentTimeMillis() / 1000)
                .build();
    }
}
