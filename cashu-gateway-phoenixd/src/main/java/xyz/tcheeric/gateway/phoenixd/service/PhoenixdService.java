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

public interface PhoenixdService {
    CreateInvoiceResponse createInvoice(CreateInvoiceParam param);
    DecodeInvoiceResponse decodeInvoice(DecodeInvoiceParam param);
    GetLightningAddressResponse getLightningAddress();
    PayBolt11InvoiceResponse payBolt11Invoice(PayBolt11InvoiceParam param);
    PayLightningAddressResponse payLightningAddress(PayLightningAddressParam param);
}
