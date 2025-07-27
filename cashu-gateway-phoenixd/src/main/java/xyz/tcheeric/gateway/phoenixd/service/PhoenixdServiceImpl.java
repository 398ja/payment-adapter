package xyz.tcheeric.gateway.phoenixd.service;

import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.phoenixd.model.response.PayBolt11InvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.PayLightningAddressResponse;
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
    public PayBolt11InvoiceResponse payBolt11Invoice(PayBolt11InvoiceParam param) {
        return new PayBolt11InvoiceRequest(param).getResponse();
    }

    @Override
    public PayLightningAddressResponse payLightningAddress(PayLightningAddressParam param) {
        return new PayLightningAddressRequest(param).getResponse();
    }
}
