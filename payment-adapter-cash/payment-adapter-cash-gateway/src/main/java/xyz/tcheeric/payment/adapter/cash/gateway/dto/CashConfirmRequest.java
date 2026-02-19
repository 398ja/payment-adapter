package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for confirming cash receipt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashConfirmRequest {

    /**
     * Actual amount received in minor currency units.
     * If not specified, uses the invoice amount.
     */
    @Min(value = 1, message = "Amount received must be positive")
    private Integer amountReceived;

    /**
     * Optional note about the transaction.
     */
    private String note;
}
