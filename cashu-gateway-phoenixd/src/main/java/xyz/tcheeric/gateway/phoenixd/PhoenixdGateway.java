package xyz.tcheeric.gateway.phoenixd;

import cashu.gateway.Gateway;
import cashu.gateway.model.Response;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.phoenixd.api.model.rest.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.api.model.rest.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.api.model.rest.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.api.model.rest.PayInvoiceResponse;
import xyz.tcheeric.phoenixd.api.model.rest.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.api.rest.BasePayRequest;
import xyz.tcheeric.phoenixd.api.rest.CreateBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.api.rest.PayBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.api.rest.PayLightningAddressRequest;
import xyz.tcheeric.phoenixd.api.rest.PayRequestFactory;
import xyz.tcheeric.util.ConfigUtil;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.UUID;

@Log
@NoArgsConstructor
public class PhoenixdGateway implements Gateway {

    private final static ConfigUtil configUtil = new ConfigUtil("phoenixd");

    @SneakyThrows
    @Override
    public String createQuote(Integer amount, String description) {

        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(Integer.valueOf(configUtil.get("expiry")));
        param.setExternalId(UUID.randomUUID().toString());
        param.setWebhookUrl(getWebhookUrl());

        // Create the invoice
        CreateBolt11InvoiceRequest request = new CreateBolt11InvoiceRequest(param);
        CreateInvoiceResponse response = request.getResponse();

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setInvoiceId(param.getExternalId());
        quote.setExpiry(param.getExpirySeconds());
        quote.setDescription(param.getDescription());
        quote.setAmount(response.getAmountSat());
        quote.setUnit(configUtil.get("currency"));
        quote.setRequest(response.getSerialized());
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);

        // Persist the GatewayQuote
        QuoteClient quoteClient = new QuoteClient();
        quoteClient.create(quote);

        // Return the quote id
        return quote.getQuoteId();
    }

    @Override
    public String createQuote(Integer amount, String lnInvoice, String description) {
        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(Integer.valueOf(configUtil.get("expiry")));
        param.setExternalId(UUID.randomUUID().toString());

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setInvoiceId(param.getExternalId());
        quote.setExpiry(param.getExpirySeconds());
        quote.setDescription(param.getDescription());
        quote.setAmount(amount);
        quote.setUnit(configUtil.get("currency"));
        quote.setRequest(lnInvoice);
        quote.setState(State.PENDING);
        quote.setDirection(Direction.SEND);

        // Persist the GatewayQuote
        QuoteClient quoteClient = new QuoteClient();
        quoteClient.create(quote);

        // Return the quote id
        return quote.getQuoteId();
    }


    @Override
    public String getRequest(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        return quote.getRequest();
    }

    @Override
    public boolean checkPaymentStatus(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        return State.CONFIRMED.equals(quote.getState());
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        String lnInvoice = quote.getRequest();

        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByLnInvoice(lnInvoice);
        return payment.getPaymentId();
    }

    @SneakyThrows
    @Override
    public String pay(String quoteId) {

        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        String request = quote.getRequest();

        assert request != null;


        BasePayRequest payRequest = PayRequestFactory.createPayRequest(request);

        assert payRequest != null;

        BasePayRequest basePayRequest = null;

        if (payRequest instanceof PayBolt11InvoiceRequest) {
            PayBolt11InvoiceParam param = new PayBolt11InvoiceParam();
            param.setAmountSat(quote.getAmount());
            param.setInvoice(request);
            basePayRequest = new PayBolt11InvoiceRequest(param);
        } else if (payRequest instanceof PayLightningAddressRequest) {
            PayLightningAddressParam param = new PayLightningAddressParam();
            param.setAmountSat(quote.getAmount());
            // TODO - Pass the message
            param.setAddress(request);
            basePayRequest = new PayLightningAddressRequest(param);
        }

        assert basePayRequest != null;
        Response response = basePayRequest.getResponse();

        if (response instanceof PayInvoiceResponse payInvoiceResponse) {

            if (payInvoiceResponse.getReason() != null) {
                throw new IllegalStateException("Payment failed: " + payInvoiceResponse.getReason());
            }

            GatewayPayment payment = new GatewayPayment();
            payment.setPaymentId(payInvoiceResponse.getPaymentId());
            payment.setLnInvoice(request);
            payment.setSourceCurrency(configUtil.get("currency"));
            payment.setPaymentHash(payInvoiceResponse.getPaymentHash());
            payment.setPaymentPreimage(payInvoiceResponse.getPaymentPreimage());
            payment.setLightningNetworkFee(payInvoiceResponse.getRoutingFeeSat());
            payment.setAmount(payInvoiceResponse.getRecipientAmountSat());
            payment.setState(State.PAID);
            payment.setPaidDate(Instant.now());
            payment.setConfirmedDate(null);

            PaymentClient client = new PaymentClient();
            client.create(payment);

            return payInvoiceResponse.getPaymentId();
        }

        // Should never happen
        throw new IllegalStateException("Invalid response type: " + response.getClass().getName());
    }

    @Override
    public Integer getAmount(String quoteId) {
        QuoteClient client = new QuoteClient();
        GatewayQuote quote = client.getByEntityId(quoteId);
        return quote.getAmount();
    }

    @Override
    public Integer getPaymentExpiry(String quoteId) {
        QuoteClient client = new QuoteClient();
        GatewayQuote quote = client.getByEntityId(quoteId);
        return quote.getExpiry();
    }

    @Override
    public Integer getFeeReserve(String quoteId) {

        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        String lnInvoice = quote.getRequest();

        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByLnInvoice(lnInvoice);
        return payment.getLightningNetworkFee();
    }

    @Override
    public String getMethod() {
        return null;
    }

    @SneakyThrows
    private URL getWebhookUrl() {
        ConfigUtil cUtil = new ConfigUtil("webhook");
        String wid = System.getProperty("wid");
        if (wid == null) {
            throw new IllegalArgumentException("Missing webhook id");
        }
        return URI.create(cUtil.get("base_url") + "?wid=" + wid).toURL();
    }

}
