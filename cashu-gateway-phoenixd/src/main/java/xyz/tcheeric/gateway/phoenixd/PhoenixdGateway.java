package xyz.tcheeric.gateway.phoenixd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.common.Gateway;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.phoenixd.common.rest.Response;
import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.phoenixd.model.response.PayInvoiceResponse;
import xyz.tcheeric.gateway.phoenixd.service.PhoenixdService;
import xyz.tcheeric.gateway.phoenixd.service.PhoenixdServiceImpl;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Gateway implementation that integrates with a running <code>phoenixd</code>
 * instance. It is responsible for creating mint and melt quotes, persisting
 * them through the REST clients and coordinating payment execution against
 * the phoenixd service.
 */
@Slf4j
@Component
@Supports({PaymentMethod.BOLT11, PaymentMethod.BOLT12, PaymentMethod.ON_CHAIN})
public class PhoenixdGateway implements Gateway {

    private static final String GATEWAY_NAME = "phoenixd";

    @Value("${phoenixd.currency}")
    private String currency;

    @Value("${phoenixd.expiry}")
    private int expiry;

    @Value("${phoenixd.lnaddress:off}")
    private String lnAddressFlag;

    @Value("${phoenixd.fee.percent}")
    private double feePercent;

    @Value("${phoenixd.fee.fixed}")
    private int fixedFee;

    @Value("${webhook.base_url}")
    private String webhookBaseUrl;

    private PhoenixdService service = new PhoenixdServiceImpl();

    public PhoenixdGateway() {
    }

    public PhoenixdGateway(PhoenixdService service) {
        this.service = service;
    }

    @SneakyThrows
    @Override
    public String createMintQuote(Integer amount, String description) {

        log.info("Creating mint quote: amount={}, description={}", amount, description);

        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(expiry);
        param.setExternalId(UUID.randomUUID().toString());
        param.setWebhookUrl(getWebhookUrl());

        // Create the invoice
        CreateInvoiceResponse response = service.createInvoice(param);

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setInvoiceId(param.getExternalId());
        quote.setExpiry(param.getExpirySeconds());
        quote.setDescription(param.getDescription());
        quote.setAmount(response.getAmountSat());
        quote.setUnit(currency);
        quote.setRequest(getRequest(response));
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);

        // Persist the GatewayQuote
        QuoteClient quoteClient = new QuoteClient();
        quoteClient.create(quote);

        log.info("Created mint quote: quoteId={}", quote.getQuoteId());

        // Return the quote id
        return quote.getQuoteId();
    }

    @Override
    public String createMeltQuote(String request) {
        log.info("Creating melt quote for request {}", request);
        if (request.startsWith("lnbc") || request.startsWith("lntb") || request.startsWith("lntbs")) {
            return createMeltQuoteLnInvoice(request);
        } else {
            return createMeltQuoteLnAddress(request);
        }
    }

    @Override
    public String createMeltQuote(Integer amount, String request, String description) {

        log.info("Creating melt quote: amount={}, description={} request={}", amount, description, request);

        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(expiry);
        param.setExternalId(UUID.randomUUID().toString());

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setInvoiceId(param.getExternalId());
        quote.setExpiry(param.getExpirySeconds());
        quote.setDescription(param.getDescription());
        quote.setAmount(amount);
        quote.setUnit(currency);
        quote.setRequest(request);
        quote.setState(State.PENDING);
        quote.setDirection(Direction.SEND);

        // Persist the GatewayQuote
        QuoteClient quoteClient = new QuoteClient();
        quoteClient.create(quote);

        log.info("Created melt quote: quoteId={}", quote.getQuoteId());

        // Return the quote id
        return quote.getQuoteId();
    }


    @Override
    public String getRequest(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        log.debug("Retrieved request for quoteId={}", quoteId);
        return quote.getRequest();
    }

    @Override
    public boolean checkPaymentStatus(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        //GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        GatewayPayment payment = new PaymentClient().getByQuoteId(quoteId);
        log.debug("Checked payment status for quoteId={}, state={}", quoteId, payment.getState());
        return State.PAID.equals(payment.getState());
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByQuoteId(quoteId);
        log.debug("Retrieved payment preimage for quoteId={}", quoteId);
        return payment.getPaymentId();
    }

    @SneakyThrows
    @Override
    public String pay(String quoteId) {

        log.info("Paying quote: quoteId={}", quoteId);

        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        String request = quote.getRequest();

        if (request == null) {
            throw new IllegalStateException("Missing payment request");
        }

        Response response;

        if (request.contains("@")) {
            PayLightningAddressParam param = new PayLightningAddressParam();
            param.setAmountSat(quote.getAmount());
            param.setAddress(request);
            response = service.payLightningAddress(param);
        } else {
            PayBolt11InvoiceParam param = new PayBolt11InvoiceParam();
            param.setAmountSat(quote.getAmount());
            param.setInvoice(request);
            response = service.payBolt11Invoice(param);
        }

        if (response == null) {
            throw new IllegalStateException("Null response from phoenixd service");
        }

        if (response instanceof PayInvoiceResponse payInvoiceResponse) {

            if (payInvoiceResponse.getReason() != null) {
                throw new IllegalStateException("Payment failed: " + payInvoiceResponse.getReason());
            }

            GatewayPayment payment = new GatewayPayment();
            payment.setPaymentId(payInvoiceResponse.getPaymentId());
            payment.setRequest(request);
            payment.setQuoteId(quoteId);
            payment.setSourceCurrency(currency);
            payment.setPaymentHash(payInvoiceResponse.getPaymentHash());
            payment.setPaymentPreimage(payInvoiceResponse.getPaymentPreimage());
            payment.setLightningNetworkFee(payInvoiceResponse.getRoutingFeeSat());
            payment.setAmount(payInvoiceResponse.getRecipientAmountSat());
            payment.setState(State.PAID);
            payment.setPaidDate(Instant.now());
            payment.setConfirmedDate(null);

            PaymentClient client = new PaymentClient();
            client.create(payment);

            log.info("Payment sent: paymentId={}", payInvoiceResponse.getPaymentId());

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
        return (int) Math.round(amount * feePercent) + fixedFee;
    }

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    private String createMeltQuoteLnInvoice(@NonNull String lnInvoice) {
        DecodeInvoiceParam param = new DecodeInvoiceParam();
        param.setInvoice(lnInvoice);
        DecodeInvoiceResponse decodeInvoiceResponse = service.decodeInvoice(param);
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
        String wid = System.getProperty("wid");
        if (wid == null) {
            throw new IllegalArgumentException("Missing webhook id");
        }
        return URI.create(webhookBaseUrl + "?wid=" + wid).toURL();
    }

    private String getRequest(@NonNull CreateInvoiceResponse response) {
        boolean lnAddressFlag = this.lnAddressFlag.equalsIgnoreCase("on");
        return lnAddressFlag ? getLightningAddress() : response.getSerialized();
    }

    private String getLightningAddress() {
        GetLightningAddressResponse resp = service.getLightningAddress();
        return resp.getLightningAddress();
    }
}
