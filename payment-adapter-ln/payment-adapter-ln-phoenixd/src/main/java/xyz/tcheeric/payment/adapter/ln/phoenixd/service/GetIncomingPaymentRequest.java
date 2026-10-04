package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import xyz.tcheeric.phoenixd.request.impl.GetRequest;

/**
 * Asks phoenixd directly whether an invoice has been paid, by its payment hash.
 *
 * <p>phoenixd-java has no request for this endpoint, so the adapter declares it on the library's
 * own {@link GetRequest}, which supplies the base URL, credentials, timeout and error handling.
 * A non-2xx answer or an unreachable node throws, which the gateway reads as "unknown".
 */
public class GetIncomingPaymentRequest extends GetRequest<IncomingPaymentParam, IncomingPaymentResponse> {

    public GetIncomingPaymentRequest(IncomingPaymentParam param) {
        super("/payments/incoming/{paymentHash}", param);
    }
}
