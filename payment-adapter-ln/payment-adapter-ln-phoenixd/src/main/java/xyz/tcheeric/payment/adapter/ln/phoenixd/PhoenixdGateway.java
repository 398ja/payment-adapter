package xyz.tcheeric.payment.adapter.ln.phoenixd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.payment.adapter.core.common.InvoiceNotPaidException;
import xyz.tcheeric.payment.adapter.core.common.PaymentStatusUnavailableException;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.common.QuoteRef;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.phoenixd.common.rest.Response;
import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayBolt11InvoiceParam;
import xyz.tcheeric.phoenixd.model.param.PayLightningAddressParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.phoenixd.model.response.PayInvoiceResponse;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.AdapterWebhookRelay;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.IncomingPaymentResponse;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.PhoenixdService;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.PhoenixdServiceImpl;

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

    private AdapterWebhookRelay webhookRelay = new AdapterWebhookRelay();

    public PhoenixdGateway() {
        loadPropertiesIfNeeded();
    }

    public PhoenixdGateway(PhoenixdService service) {
        this.service = service;
        loadPropertiesIfNeeded();
    }

    PhoenixdGateway(PhoenixdService service, AdapterWebhookRelay webhookRelay) {
        this(service);
        this.webhookRelay = webhookRelay;
    }

    @Override
    public String createMintQuote(Integer amount, String description) {
        // Generate a single UUID for both quoteId and externalId
        // This allows external clients to use quoteId for phoenixd lookups (e.g., mockpay)
        return createMintQuote(UUID.randomUUID().toString(), amount, description);
    }

    /**
     * Creates the invoice under the caller's id, so the caller can record the quote first.
     *
     * <p>phoenixd takes the id as {@code externalId} and this gateway already used one UUID for
     * both, so honouring a supplied id changes nothing about how the invoice is created or looked
     * up: the only difference is who chose the value.
     */
    @SneakyThrows
    @Override
    public String createMintQuote(String quoteId, Integer amount, String description) {
        if (quoteId == null || quoteId.isBlank()) {
            throw new IllegalArgumentException("quoteId must not be blank");
        }

        log.info("Creating mint quote: quote_ref={}, amount={}, description={}", QuoteRef.of(quoteId), amount, description);

        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(expiry);
        param.setExternalId(quoteId);
        param.setWebhookUrl(getWebhookUrl());

        // Create the invoice
        CreateInvoiceResponse response = service.createInvoice(param);

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(quoteId);
        quote.setInvoiceId(quoteId);
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

        log.info("Created mint quote: quote_ref={}", QuoteRef.of(quote.getQuoteId()));

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

        // Generate a single UUID for both quoteId and externalId
        // This allows external clients to use quoteId for phoenixd lookups (e.g., mockpay)
        String quoteId = UUID.randomUUID().toString();

        // Create the invoice param
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setDescription(description);
        param.setAmountSat(amount);
        param.setExpirySeconds(expiry);
        param.setExternalId(quoteId);

        // Create the GatewayQuote
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(quoteId);
        quote.setInvoiceId(quoteId);
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

        log.info("Created melt quote: quote_ref={}", QuoteRef.of(quote.getQuoteId()));

        // Return the quote id
        return quote.getQuoteId();
    }


    @Override
    public String getRequest(String quoteId) {
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        log.debug("Retrieved request for quote_ref={}", QuoteRef.of(quoteId));
        return quote.getRequest();
    }

    /**
     * Answers whether the quote is paid, and only ever says "not paid" on a definite answer.
     *
     * <p>A stored quote that is not PAID and has no Payment record is not yet known to be paid,
     * but the store only learns of a payment from phoenixd's webhook, and a lost webhook would
     * leave a paid invoice reading unpaid for good. So for a RECEIVE quote the gateway asks
     * phoenixd directly before answering:
     * <ul>
     *   <li>phoenixd says paid: the payment is recorded (the quote is marked PAID), phoenixd's
     *   callback is replayed to the adapter's webhook so the mint is told as it would have been,
     *   and the answer is {@code true};</li>
     *   <li>phoenixd says not paid, or holds no payment for the hash: {@code false}, which the
     *   mint reports as a 200 with state UNPAID;</li>
     *   <li>phoenixd cannot be asked (unreachable, timeout, 5xx):
     *   {@link PaymentStatusUnavailableException}, the "unknown" answer.</li>
     * </ul>
     *
     * <p>A quote and Payment the store both answer 404 for stays {@link InvoiceNotPaidException},
     * as before. A store lookup that fails for any other reason (timeout, connection reset, a 5xx)
     * throws {@link PaymentStatusUnavailableException}: falling through to "not paid" there
     * reported a PAID quote as unpaid, which the mint answers as 20001 and a caller past the
     * quote's expiry read as "forget it" (#253).
     */
    @Override
    public boolean checkPaymentStatus(String quoteId) {
        // First check the Quote state directly (supports RECEIVE quotes where payment record may not exist)
        GatewayQuote quote = findQuote(quoteId);
        if (quote != null && isSettled(quote.getState())) {
            log.debug("phoenixd_gateway quote_paid quote_ref={} state={}", QuoteRef.of(quoteId), quote.getState());
            return true;
        }

        // Fall back to checking Payment record (for backward compatibility with MELT quotes)
        GatewayPayment payment;
        try {
            payment = new PaymentClient().getByQuoteId(quoteId);
        } catch (HttpClientErrorException.NotFound notFound) {
            if (quote != null) {
                return askPhoenixd(quote);
            }
            log.warn("phoenixd_gateway payment_missing quote_ref={} state=UNPAID reason=not_recorded",
                    QuoteRef.of(quoteId));
            throw new InvoiceNotPaidException(
                    quoteId,
                    "Payment record not found for quote " + QuoteRef.of(quoteId),
                    notFound
            );
        } catch (RuntimeException e) {
            throw statusUnavailable(quoteId, "payment_lookup_failed", e);
        }
        log.debug("phoenixd_gateway payment_state quote_ref={} state={}", QuoteRef.of(quoteId), payment.getState());
        return isSettled(payment.getState());
    }

    /**
     * PAID or CONFIRMED. CONFIRMED is the state after PAID, so a quote or payment that has moved
     * on to it is still paid; reading only PAID would report a settled payment as unpaid.
     */
    private static boolean isSettled(State state) {
        return State.PAID.equals(state) || State.CONFIRMED.equals(state);
    }

    /**
     * The definite answer for a stored, not-PAID quote with no Payment record: phoenixd's own.
     *
     * <p>Only an incoming (RECEIVE) quote can have been paid without this adapter doing the
     * paying, so only those are looked up. A SEND quote with no Payment record has not been paid
     * by this gateway, which is already a definite answer.
     */
    private boolean askPhoenixd(GatewayQuote quote) {
        String quoteId = quote.getQuoteId() != null ? quote.getQuoteId() : "";
        String ref = QuoteRef.of(quoteId);
        if (quote.getDirection() != Direction.RECEIVE) {
            log.debug("phoenixd_gateway quote_unpaid quote_ref={} state={} direction={}",
                    ref, quote.getState(), quote.getDirection());
            return false;
        }
        String request = quote.getRequest();
        if (request == null || !request.toLowerCase().startsWith("ln") || request.contains("@")) {
            // No per-quote invoice to look up (a Lightning address request): phoenixd cannot
            // tell this quote's payment apart, and its webhook could not match it either.
            log.debug("phoenixd_gateway quote_unpaid quote_ref={} state={} reason=no_invoice_to_check",
                    ref, quote.getState());
            return false;
        }

        IncomingPaymentResponse incoming;
        try {
            DecodeInvoiceParam decode = new DecodeInvoiceParam();
            decode.setInvoice(request);
            DecodeInvoiceResponse decoded = service.decodeInvoice(decode);
            String paymentHash = decoded == null ? null : decoded.getPaymentHash();
            if (paymentHash == null || paymentHash.isBlank()) {
                throw new IllegalStateException("phoenixd decoded the invoice without a payment hash");
            }
            incoming = service.getIncomingPayment(paymentHash);
        } catch (RuntimeException e) {
            throw statusUnavailable(quoteId, "phoenixd_lookup_failed", e);
        }

        if (incoming == null || !Boolean.TRUE.equals(incoming.getPaid())) {
            log.debug("phoenixd_gateway quote_unpaid quote_ref={} state={} phoenixd=unpaid", ref, quote.getState());
            return false;
        }

        reconcilePaid(quote, incoming);
        return true;
    }

    /**
     * Records a payment phoenixd has but whose webhook never reached the adapter, then nudges the
     * mint the way the webhook would have.
     *
     * <p><b>Neither step can change the answer.</b> phoenixd has the money, so the quote is paid,
     * and this method cannot throw: every failure is caught and logged. Turning a failure here
     * into "not paid" would make a caller forget a paid voucher.
     *
     * <p><b>The PAID stamp is what matters.</b> Once the quote reads PAID, every later status
     * check returns {@code true} from the first branch of {@link #checkPaymentStatus} without
     * asking phoenixd again, and the mint's {@code MintQuoteStatusTask} and {@code MintTask} both
     * read paid through this gateway. If the stamp fails, the next status check asks phoenixd
     * again.
     *
     * <p><b>The replay is best effort.</b> It makes the adapter's webhook handler forward the
     * payment to the mint now, which the voucher route needs (cashu-mint funds a voucher from the
     * {@code webhook_event} that forward creates). It goes to the adapter the REST clients just
     * stamped the quote through ({@code GATEWAY_API_BASE_URL}), not to {@code webhook.base_url}:
     * inside cashu-mint the gateway is built by reflection, so {@code @Value} is never applied and
     * that property is the bundled {@code http://localhost:9090/webhook}, where nothing listens.
     * If the replay fails anyway, the quote is PAID with no {@code mintNotifiedAt}, which the
     * adapter's {@code PaidQuoteForwardReconciler} re-delivers ({@code MINT_WEBHOOK_RECONCILE_ENABLED}),
     * so it is a WARN rather than an alert.
     */
    private void reconcilePaid(GatewayQuote quote, IncomingPaymentResponse incoming) {
        String ref = QuoteRef.of(quote.getQuoteId());
        log.warn("phoenixd_gateway payment_reconciled quote_ref={} reason=webhook_not_received "
                + "- phoenixd reports the invoice paid but the adapter never recorded it", ref);
        QuoteClient quoteClient;
        try {
            quoteClient = new QuoteClient();
            quoteClient.markPaid(quote.getId());
        } catch (RuntimeException e) {
            log.error("[alert] phoenixd_gateway reconcile_record_failed quote_ref={} error={} "
                    + "- paid at phoenixd, not recorded; the next status check retries",
                    ref, e.getClass().getSimpleName());
            return;
        }
        try {
            boolean relayed = webhookRelay.relayPaymentReceived(replayUrl(quoteClient), quote.getQuoteId(),
                    receivedAmount(incoming, quote), incoming.getPaymentHash());
            if (!relayed) {
                log.warn("phoenixd_gateway reconcile_replay_failed quote_ref={} reason=refused "
                        + "- recorded PAID; left for PaidQuoteForwardReconciler", ref);
            }
        } catch (RuntimeException e) {
            log.warn("phoenixd_gateway reconcile_replay_failed quote_ref={} error={} "
                    + "- recorded PAID; left for PaidQuoteForwardReconciler", ref, e.getClass().getSimpleName());
        }
    }

    /** What phoenixd says arrived, or the invoiced amount when that is missing or not an int. */
    private static Integer receivedAmount(IncomingPaymentResponse incoming, GatewayQuote quote) {
        Long received = incoming.getReceivedSat();
        if (received == null || received < 0 || received > Integer.MAX_VALUE) {
            return quote.getAmount();
        }
        return received.intValue();
    }

    /**
     * The adapter's phoenixd webhook, on the host the REST clients use. Falls back to
     * {@code webhook.base_url} only when the client has no base URL (a test double).
     */
    private URL replayUrl(QuoteClient quoteClient) {
        String adapter = quoteClient.getBaseUrl();
        if (adapter == null || adapter.isBlank()) {
            return getWebhookUrl();
        }
        try {
            return URI.create(adapter.replaceAll("/+$", "") + "/webhook/" + GATEWAY_NAME).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            return getWebhookUrl();
        }
    }

    /**
     * The stored quote, or {@code null} when the store definitely does not have it (404).
     * Any other failure means its state is unknown.
     */
    private GatewayQuote findQuote(String quoteId) {
        try {
            return new QuoteClient().getByEntityId(quoteId);
        } catch (HttpClientErrorException.NotFound notFound) {
            log.debug("phoenixd_gateway quote_not_found quote_ref={}", QuoteRef.of(quoteId));
            return null;
        } catch (RuntimeException e) {
            throw statusUnavailable(quoteId, "quote_lookup_failed", e);
        }
    }

    /**
     * The "unknown" answer, built so that nothing a caller logs can carry the quote id.
     *
     * <p>The quote id is a bearer claim on the payment (cashu-mint#531). The obvious leak is the
     * message; the less obvious one is the cause. A RestTemplate I/O error's message is
     * {@code I/O error on GET request for ".../findByQuoteId?quoteId=<raw id>"}, and a chained
     * cause is printed in full by every caller that logs the exception with its stack trace. So
     * this logs only the cause's class name, and chains a {@link RedactedCause}: the original
     * class name and stack trace, without its message or its own causes.
     */
    private static PaymentStatusUnavailableException statusUnavailable(String quoteId, String reason,
                                                                       RuntimeException cause) {
        String ref = QuoteRef.of(quoteId);
        log.warn("phoenixd_gateway {} quote_ref={} state=UNKNOWN error={}",
                reason, ref, cause.getClass().getSimpleName());
        return new PaymentStatusUnavailableException(
                quoteId,
                "Payment status unavailable for quote " + ref + " (" + reason + ")",
                new RedactedCause(cause)
        );
    }

    /**
     * A stand-in for a lookup failure whose message may carry a quote id in a URL: the original's
     * class name as the message, its stack trace for the "where", and nothing else.
     */
    static final class RedactedCause extends RuntimeException {
        RedactedCause(Throwable original) {
            super(original.getClass().getName() + " (message redacted: may carry the quote id)",
                    null, false, true);
            setStackTrace(original.getStackTrace());
        }
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        PaymentClient client = new PaymentClient();
        GatewayPayment payment = client.getByQuoteId(quoteId);
        log.debug("Retrieved payment preimage for quote_ref={}", QuoteRef.of(quoteId));
        return payment.getPaymentId();
    }

    @Override
    public String pay(String quoteId) {

        log.info("Paying quote: quote_ref={}", QuoteRef.of(quoteId));

        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        if (quote == null) {
            throw new IllegalStateException("Unknown quote: " + QuoteRef.of(quoteId));
        }
        if (!quoteId.equals(quote.getQuoteId())) {
            throw new IllegalStateException("Mismatched quote: requested=" + QuoteRef.of(quoteId)
                    + ", stored=" + QuoteRef.of(quote.getQuoteId()));
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
    public java.time.Instant getCreatedAt(String quoteId) {
        QuoteClient client = new QuoteClient();
        GatewayQuote quote = client.getByEntityId(quoteId);
        return quote != null ? quote.getCreatedAt() : null;
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

    @Override
    public PaymentType getPaymentType() {
        return PaymentType.LIGHTNING_NETWORK;
    }

    @Override
    public String getGatewayId() {
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
