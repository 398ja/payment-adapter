package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.phoenixd.common.rest.Request;

/**
 * Path parameter for phoenixd's {@code GET /payments/incoming/{paymentHash}}.
 *
 * <p>The field name is the placeholder name: phoenixd-java substitutes {@code {paymentHash}} from
 * it by reflection.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IncomingPaymentParam implements Request.Param {

    private String paymentHash;
}
