package xyz.tcheeric.payment.adapter.cash.nostr.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted payload for CashIntent event (kind 5201).
 * Sent by customer to signal intent to pay.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashIntentPayload {

    /**
     * Must match invoice ref
     */
    @JsonProperty("ref")
    private String ref;

    /**
     * Customer's ephemeral public key (C_e)
     */
    @JsonProperty("from")
    private String from;

    /**
     * Optional 4-6 digit code for counter verification
     */
    @JsonProperty("proof")
    private String proof;

    /**
     * Customer's timestamp (Unix epoch seconds)
     */
    @JsonProperty("ts")
    private Long ts;
}
