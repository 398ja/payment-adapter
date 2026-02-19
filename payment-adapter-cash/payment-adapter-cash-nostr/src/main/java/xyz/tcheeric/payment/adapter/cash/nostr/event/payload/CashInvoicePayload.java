package xyz.tcheeric.payment.adapter.cash.nostr.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encrypted payload for CashInvoice event (kind 5200).
 * Contains invoice details that may be encrypted using NIP-44.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashInvoicePayload {

    /**
     * Amount in minor currency units (cents for USD, satoshis for BTC)
     */
    @JsonProperty("amount")
    private Integer amount;

    /**
     * ISO 4217 currency code (null/omit for satoshis)
     */
    @JsonProperty("fiat")
    private String fiat;

    /**
     * Optional short description (max 140 chars)
     */
    @JsonProperty("memo")
    private String memo;

    /**
     * Unique invoice reference nonce
     */
    @JsonProperty("ref")
    private String ref;

    /**
     * Unix timestamp expiry
     */
    @JsonProperty("exp")
    private Long exp;

    /**
     * Encryption mode indicator ("nip44" or "nip04")
     */
    @JsonProperty("enc")
    private String enc;
}
