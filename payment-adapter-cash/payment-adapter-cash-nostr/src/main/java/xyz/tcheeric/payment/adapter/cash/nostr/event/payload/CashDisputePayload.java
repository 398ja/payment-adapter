package xyz.tcheeric.payment.adapter.cash.nostr.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted payload for CashDispute event (kind 5204).
 * Optional dispute record for manual review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashDisputePayload {

    /**
     * Invoice reference
     */
    @JsonProperty("ref")
    private String ref;

    /**
     * Claim type ("amount_dispute", "no_receipt", "fraud")
     */
    @JsonProperty("claim")
    private String claim;

    /**
     * Human-readable explanation
     */
    @JsonProperty("description")
    private String description;

    /**
     * Hash of attached evidence (off-band storage)
     */
    @JsonProperty("evidence_hash")
    private String evidenceHash;
}
