package xyz.tcheeric.gateway.phoenixd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.common.Gateway;
import xyz.tcheeric.gateway.common.InvoiceNotPaidException;
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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.io.InputStream;
import java.util.Properties;

/**
 * Gateway implementation that integrates with a running <code>phoenixd</code>
 * instance. It is responsible for creating mint and melt quotes, persisting
 * them through the REST clients and coordinating payment execution against
 * the phoenixd service.
 */
@Slf4j
@Component
@PropertySource("classpath:phoenixd.properties")
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
        loadPropertiesIfNeeded();
    }

    public PhoenixdGateway(PhoenixdService service) {
        this.service = service;
        loadPropertiesIfNeeded();
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

        // First check the Quote state directly (supports RECEIVE quotes where payment record may not exist)
        try {
            GatewayQuote quote = quoteClient.getByEntityId(quoteId);
            if (State.PAID.equals(quote.getState())) {
                log.debug("phoenixd_gateway quote_paid quoteId={} state={}", quoteId, quote.getState());
                return true;
            }
        } catch (Exception e) {
            log.warn("phoenixd_gateway quote_lookup_failed quoteId={} error={}", quoteId, e.getMessage());
        }

        // Fall back to checking Payment record (for backward compatibility with MELT quotes)
        try {
            GatewayPayment payment = new PaymentClient().getByQuoteId(quoteId);
            log.debug("Checked payment status for quoteId={}, state={}", quoteId, payment.getState());
            return State.PAID.equals(payment.getState());
        } catch (HttpClientErrorException.NotFound notFound) {
            log.warn("phoenixd_gateway payment_missing quoteId={} state=UNPAID reason=not_recorded", quoteId);
            throw new InvoiceNotPaidException(
                    quoteId,
                    "Payment record not found for quote " + quoteId,
                    notFound
            );
        }
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByQuoteId(quoteId);
        log.debug("Retrieved payment preimage for quoteId={}", quoteId);
        return payment.getPaymentId();
    }

    @Override
    public String pay(String quoteId) {

        log.info("Paying quote: quoteId={}", quoteId);

        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        if (quote == null) {
            throw new IllegalStateException("Unknown quoteId: " + quoteId);
        }
        if (!quoteId.equals(quote.getQuoteId())) {
            throw new IllegalStateException("Mismatched quoteId: requested=" + quoteId + ", stored=" + quote.getQuoteId());
        }
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

        PayInvoiceResponse payInvoiceResponse = (PayInvoiceResponse) response;
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

    private URL getWebhookUrl() {
        try {
            if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
                throw new IllegalStateException("Missing configuration: 'webhook.base_url' is not set. Ensure phoenixd.properties is on the classpath or Spring @Value injection is configured.");
            }
            String normalized = webhookBaseUrl.replaceAll("/+$", "");
            return URI.create(normalized + "/" + GATEWAY_NAME).toURL();
        } catch (MalformedURLException e) {
            log.error("Invalid webhook base URL: {}", webhookBaseUrl, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads properties from classpath file 'phoenixd.properties' when this class is
     * instantiated outside of a Spring context (i.e., @Value fields are not injected).
     * Existing non-null fields are not overridden.
     */
    private void loadPropertiesIfNeeded() {
        boolean needsLoad = currency == null || lnAddressFlag == null || webhookBaseUrl == null;
        if (!needsLoad) {
            return;
        }

        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("phoenixd.properties")) {
            if (in == null) {
                log.warn("phoenixd.properties not found on classpath; relying on defaults/@Value");
                // Provide safe defaults where applicable; critical ones remain null to fail fast
                if (currency == null) currency = "sat";
                if (lnAddressFlag == null) lnAddressFlag = "off";
                // expiry/fees may remain at Java defaults if injected elsewhere
                return;
            }
            Properties p = new Properties();
            p.load(in);

            if (currency == null) {
                currency = p.getProperty("phoenixd.currency", "sat");
            }
            if (expiry == 0) {
                // Prefer 'phoenixd.expiry'; retain backward compat if only 'phoenixd.expiration' is present
                String exp = p.getProperty("phoenixd.expiry", p.getProperty("phoenixd.expiration", "60"));
                try { expiry = Integer.parseInt(exp); } catch (NumberFormatException ignored) { expiry = 60; }
            }
            if (lnAddressFlag == null) {
                lnAddressFlag = p.getProperty("phoenixd.lnaddress", "off");
            }
            if (feePercent == 0.0d) {
                String fp = p.getProperty("phoenixd.fee.percent", "0.0");
                try { feePercent = Double.parseDouble(fp); } catch (NumberFormatException ignored) { /* keep default */ }
            }
            if (fixedFee == 0) {
                String ff = p.getProperty("phoenixd.fee.fixed", "0");
                try { fixedFee = Integer.parseInt(ff); } catch (NumberFormatException ignored) { /* keep default */ }
            }
            if (webhookBaseUrl == null) {
                webhookBaseUrl = p.getProperty("webhook.base_url");
            }

            // Propagate phoenixd client properties to System properties if not already set
            String baseUrl = p.getProperty("phoenixd.base_url", "http://localhost:9740");
            if (System.getProperty("phoenixd.base_url") == null || System.getProperty("phoenixd.base_url").isBlank()) {
                System.setProperty("phoenixd.base_url", baseUrl);
            }
            String user = p.getProperty("phoenixd.username", "");
            if (!user.isBlank() && (System.getProperty("phoenixd.username") == null)) {
                System.setProperty("phoenixd.username", user);
            }
            String pass = p.getProperty("phoenixd.password", "");
            if (!pass.isBlank() && (System.getProperty("phoenixd.password") == null)) {
                System.setProperty("phoenixd.password", pass);
            }
            String timeout = p.getProperty("phoenixd.timeout", "");
            if (!timeout.isBlank() && (System.getProperty("phoenixd.timeout") == null)) {
                System.setProperty("phoenixd.timeout", timeout);
            }
        } catch (Exception e) {
            log.error("Failed to load phoenixd.properties from classpath", e);
        }
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
