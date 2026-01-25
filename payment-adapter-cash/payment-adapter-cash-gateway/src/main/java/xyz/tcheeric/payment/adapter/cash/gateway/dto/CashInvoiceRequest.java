package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for creating a cash invoice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashInvoiceRequest {

    /**
     * Amount in minor currency units (e.g., cents, satoshis).
     */
    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be positive")
    private Integer amount;

    /**
     * ISO 4217 currency code (e.g., "USD", "EUR").
     * If null, amount is in satoshis.
     */
    @Size(min = 3, max = 3, message = "Fiat currency code must be 3 characters")
    private String fiat;

    /**
     * Optional memo/description for the invoice.
     */
    @Size(max = 256, message = "Memo must be 256 characters or less")
    private String memo;

    /**
     * Time-to-live in seconds. Defaults to 300 (5 minutes) if not specified.
     */
    @Min(value = 60, message = "TTL must be at least 60 seconds")
    private Integer ttlSeconds;

    /**
     * Relay URLs to use. If not specified, defaults are used.
     */
    @Size(max = 5, message = "Maximum 5 relay URLs allowed")
    private List<String> relayUrls;
}
