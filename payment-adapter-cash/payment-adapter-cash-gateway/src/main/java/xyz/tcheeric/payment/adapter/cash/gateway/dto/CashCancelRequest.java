package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for cancelling a cash invoice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashCancelRequest {

    /**
     * Cancellation reason code. Valid values:
     * <ul>
     *   <li>cash.timeout - Customer took too long</li>
     *   <li>cash.fraud_suspected - Suspected fraud</li>
     *   <li>cash.counterfeit - Counterfeit notes</li>
     *   <li>cash.customer_left - Customer left without completing</li>
     *   <li>cash.merchant_request - Cancelled by merchant</li>
     * </ul>
     */
    private String reason;

    /**
     * Optional additional note about cancellation.
     */
    private String note;
}
