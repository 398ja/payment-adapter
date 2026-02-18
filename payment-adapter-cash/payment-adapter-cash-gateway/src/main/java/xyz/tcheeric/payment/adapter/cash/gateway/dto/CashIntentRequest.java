package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for customer submitting intent to pay a cash invoice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashIntentRequest {

    /**
     * Customer's Nostr public key (hex-encoded, 64 chars).
     */
    @Size(min = 64, max = 66)
    @Pattern(regexp = "^[0-9a-fA-F]+$", message = "Must be a hex string")
    private String customerPubkey;
}
