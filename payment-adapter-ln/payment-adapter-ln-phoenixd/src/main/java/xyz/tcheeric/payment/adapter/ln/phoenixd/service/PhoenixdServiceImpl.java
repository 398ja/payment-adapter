package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.phoenixd.model.response.PayBolt11InvoiceInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.PayLightningAddressInvoiceResponse;
import xyz.tcheeric.phoenixd.request.impl.rest.CreateBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.DecodeInvoiceRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.GetLightningAddressRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.PayBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.PayLightningAddressRequest;

public class PhoenixdServiceImpl implements PhoenixdService {
    @Override
    public CreateInvoiceResponse createInvoice(CreateInvoiceParam param) {
        return new CreateBolt11InvoiceRequest(param).getResponse();
    }

    @Override
    public DecodeInvoiceResponse decodeInvoice(DecodeInvoiceParam param) {
        return new DecodeInvoiceRequest(param).getResponse();
    }

    @Override
    public GetLightningAddressResponse getLightningAddress() {
        return new GetLightningAddressRequest().getResponse();
    }

    @Override
    public PayBolt11InvoiceInvoiceResponse payBolt11Invoice(PayBolt11InvoiceParam param) {
        return new PayBolt11InvoiceRequest(param).getResponse();
    }

    @Override
    public PayLightningAddressInvoiceResponse payLightningAddress(PayLightningAddressParam param) {
        return new PayLightningAddressRequest(param).getResponse();
    }

    @Override
    public IncomingPaymentResponse getIncomingPayment(String paymentHash) {
        try {
            return new GetIncomingPaymentRequest(new IncomingPaymentParam(paymentHash)).getResponse();
        } catch (Exception e) {
            // phoenixd-java reports a non-2xx answer as an IOException whose message starts with
            // the status ("Failed HTTP request: 404 ..."). A 404 is phoenixd saying it holds no
            // payment for this hash, which is a definite answer; everything else is not.
            String message = e.getMessage();
            if (message != null && message.startsWith("Failed HTTP request: 404")) {
                return null;
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("phoenixd incoming payment lookup failed", e);
        }
    }
}
