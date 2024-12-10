package xyz.tcheeric.gateway.phoenixd;

import cashu.common.annotation.Supports;
import cashu.common.model.PaymentMethod;
import cashu.gateway.Gateway;
import cashu.gateway.model.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NoArgsConstructor;
import lombok.NonNull;
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
import xyz.tcheeric.phoenixd.api.model.rest.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.api.model.rest.DecodeInvoiceResponse;
import xyz.tcheeric.phoenixd.api.model.rest.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.api.model.rest.PayInvoiceResponse;
import xyz.tcheeric.phoenixd.api.model.rest.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.api.rest.BasePayRequest;
import xyz.tcheeric.phoenixd.api.rest.CreateBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.api.rest.DecodeInvoiceRequest;
import xyz.tcheeric.phoenixd.api.rest.PayBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.api.rest.PayLightningAddressRequest;
import xyz.tcheeric.phoenixd.api.rest.PayRequestFactory;
import xyz.tcheeric.util.ConfigUtil;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Log
@NoArgsConstructor
@Supports({PaymentMethod.BOLT11, PaymentMethod.BOLT12, PaymentMethod.ON_CHAIN})
public class PhoenixdGateway implements Gateway {

    private static final String GATEWAY_NAME = "phoenixd";

    private final static ConfigUtil configUtil = new ConfigUtil("phoenixd");

    @SneakyThrows
    @Override
    public String createMintQuote(Integer amount, String description) {

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
    public String createMeltQuote(String request) {
        if (request.startsWith("lnbc") || request.startsWith("lntb") || request.startsWith("lntbs")) {
            return createMeltQuoteLnInvoice(request);
        } else {
            return createMeltQuoteLnAddress(request);
        }
    }

    @Override
    public String createMeltQuote(Integer amount, String request, String description) {

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
        quote.setRequest(request);
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
        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByQuoteId(quoteId);
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
            payment.setRequest(request);
            payment.setQuoteId(quoteId);
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
/*
        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByQuoteId(quoteId);
        return payment.getLightningNetworkFee();
*/
        GatewayQuote quote = new QuoteClient().getByEntityId(quoteId);
        Integer amount = quote.getAmount();
        Double feePercent = Double.valueOf(configUtil.get("fee.percent"));
        Integer fixedFee = Integer.valueOf(configUtil.get("fee.fixed"));
        return (int) Math.round(amount * feePercent) + fixedFee;
    }

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    private String createMeltQuoteLnInvoice(@NonNull String lnInvoice) {
        DecodeInvoiceParam param = new DecodeInvoiceParam();
        param.setInvoice(lnInvoice);
        DecodeInvoiceResponse decodeInvoiceResponse = new DecodeInvoiceRequest(param).getResponse();
        return createMeltQuote(decodeInvoiceResponse.getAmount(), lnInvoice, decodeInvoiceResponse.getDescription());
    }

    private String createMeltQuoteLnAddress(@NonNull String request) {
        try {
            // Decode the base64 encoded request
            byte[] decodedBytes = Base64.getDecoder().decode(request);
            String decodedString = new String(decodedBytes);

            // Parse the JSON string
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(decodedString);

            // Extract the lnAddress, amount, and description attributes
            String lnAddress = jsonNode.get("lnAddress").asText();
            int amount = jsonNode.get("amount").asInt();
            String description = jsonNode.get("description").asText();

            // Use the extracted values as needed
            // For example, you can create a melt quote using these values
            return createMeltQuote(amount, lnAddress, description);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode and parse the request", e);
        }
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
