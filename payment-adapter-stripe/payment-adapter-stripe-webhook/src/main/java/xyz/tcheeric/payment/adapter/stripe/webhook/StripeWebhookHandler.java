package xyz.tcheeric.payment.adapter.stripe.webhook;

import xyz.tcheeric.payment.adapter.core.common.QuoteRef;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.WebhookOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.DefaultStripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

@Slf4j
public class StripeWebhookHandler implements WebhookHandler<StripeWebhookPayload> {

    private static final String PAYMENT_TYPE = "stripe";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProcessedStripeWebhookEventRepository processedEventRepository;
    private StripePaymentReferenceRepository paymentReferenceRepository;
    private QuoteClient quoteClient;
    private PaymentClient paymentClient;
    private StripeWebhookSignatureVerifier signatureVerifier;

    /**
     * What to do when a coupon purchase is paid, or null in a deployment that
     * sells no coupons.
     *
     * <p>Optional injection, like the repositories above, because this module
     * loads via ServiceLoader SPI before Spring exists. Null is a legitimate
     * configuration — a mint-only deployment never sees a direct charge — but
     * it is NOT a licence to drop a purchase that does arrive; see
     * {@link #handlePaidPurchase}.
     */
    private StripePurchaseListener purchaseListener;

    /**
     * imani-deploy#210. Global registry by default, because this class is first built by
     * ServiceLoader before Spring exists; Spring replaces it with the application's registry.
     */
    private PaymentMetrics metrics = new PaymentMetrics();

    @Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry);
        this.metrics.preRegisterWebhooks(Source.CARD)
                .preRegister(Source.CARD, Status.SUCCEEDED, Status.FAILED, Status.REFUNDED, Status.DISPUTED,
                        Status.CANCELLED);
    }

    /**
     * How long a PROCESSING mark may stand before it is read as a crash and taken over (S5b).
     * Far above the slowest honest run: one DB transaction and a listener call, no network.
     */
    static final java.time.Duration DEFAULT_PROCESSING_STALE_AFTER = java.time.Duration.ofMinutes(2);

    private java.time.Duration processingStaleAfter = DEFAULT_PROCESSING_STALE_AFTER;

    void setProcessingStaleAfter(java.time.Duration processingStaleAfter) {
        this.processingStaleAfter = processingStaleAfter;
    }

    /**
     * No-arg constructor required by ServiceLoader SPI.
     * Repositories must be injected via {@code @Autowired} setters before handling webhooks.
     * In a non-Spring environment, {@link #handle} will throw until repositories are set.
     */
    public StripeWebhookHandler() {
        this.quoteClient = new QuoteClient();
        this.paymentClient = new PaymentClient();
        this.signatureVerifier = new DefaultStripeWebhookSignatureVerifier(
                System.getenv("STRIPE_WEBHOOK_SECRET"),
                parseTolerance(System.getenv("STRIPE_WEBHOOK_TOLERANCE_SECONDS"))
        );
        log.info("StripeWebhookHandler loaded via SPI; repositories will be injected by Spring context");
    }

    public StripeWebhookHandler(ProcessedStripeWebhookEventRepository processedEventRepository,
                                StripePaymentReferenceRepository paymentReferenceRepository,
                                QuoteClient quoteClient,
                                PaymentClient paymentClient,
                                StripeWebhookSignatureVerifier signatureVerifier) {
        this.processedEventRepository = processedEventRepository;
        this.paymentReferenceRepository = paymentReferenceRepository;
        this.quoteClient = quoteClient;
        this.paymentClient = paymentClient;
        this.signatureVerifier = signatureVerifier;
    }

    @Autowired(required = false)
    public void setPurchaseListener(StripePurchaseListener purchaseListener) {
        this.purchaseListener = purchaseListener;
    }

    @Autowired(required = false)
    public void setProcessedEventRepository(ProcessedStripeWebhookEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Autowired(required = false)
    public void setPaymentReferenceRepository(StripePaymentReferenceRepository paymentReferenceRepository) {
        this.paymentReferenceRepository = paymentReferenceRepository;
    }

    @Autowired(required = false)
    public void setQuoteClient(QuoteClient quoteClient) {
        this.quoteClient = quoteClient;
    }

    @Autowired(required = false)
    public void setPaymentClient(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Autowired(required = false)
    public void setSignatureVerifier(StripeWebhookSignatureVerifier signatureVerifier) {
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    public String getPaymentType() {
        return PAYMENT_TYPE;
    }

    @Override
    public StripeWebhookPayload parsePayload(HttpServletRequest request) throws WebhookParseException {
        String rawPayload = readBody(request);
        metrics.webhook(Source.CARD, WebhookOutcome.RECEIVED);
        if (StringUtils.isBlank(rawPayload)) {
            throw new WebhookParseException("Empty Stripe webhook payload");
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawPayload);
            JsonNode eventObject = root.path("data").path("object");

            return StripeWebhookPayload.builder()
                    .eventId(requiredText(root, "id"))
                    .eventType(requiredText(root, "type"))
                    .eventTimestamp(Instant.ofEpochSecond(root.path("created").asLong(Instant.now().getEpochSecond())))
                    .rawPayload(rawPayload)
                    .quoteId(optionalText(eventObject.path("metadata").path("quote_id")))
                    .checkoutSessionId(resolveCheckoutSessionId(root.path("type").asText(), eventObject))
                    .paymentIntentId(resolveText(eventObject, "payment_intent"))
                    .chargeId(resolveChargeId(root.path("type").asText(), eventObject))
                    .amountTotal(resolveAmount(eventObject))
                    .refundedAmountMinor(resolveRefundedAmount(eventObject))
                    .currency(optionalText(eventObject.path("currency")))
                    .livemode(root.path("livemode").asBoolean(false))
                    .status(optionalText(eventObject.path("status")))
                    .paymentStatus(optionalText(eventObject.path("payment_status")))
                    // Top-level, not inside data.object: Stripe puts the account
                    // on the event because it describes whose event it is.
                    .connectedAccountId(optionalText(root.path("account")))
                    .metadata(readMetadata(eventObject.path("metadata")))
                    .build();
        } catch (IOException e) {
            throw new WebhookParseException("Failed to parse Stripe webhook payload", e);
        }
    }

    @Override
    public void validateSignature(StripeWebhookPayload payload, HttpServletRequest request) throws WebhookSignatureException {
        if (signatureVerifier == null) {
            throw new WebhookSignatureException("Stripe webhook handler is missing a signature verifier");
        }
        try {
            signatureVerifier.verify(payload.getRawPayload(), request.getHeader("Stripe-Signature"));
        } catch (WebhookSignatureException e) {
            metrics.webhook(Source.CARD, WebhookOutcome.SIGNATURE_REJECTED);
            throw e;
        }
        metrics.webhook(Source.CARD, WebhookOutcome.VERIFIED);
    }

    @Override
    public WebhookResult handle(StripeWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        long started = System.nanoTime();
        ensureDependencies();
        ProcessedStripeWebhookEvent eventRecord;
        try {
            eventRecord = startEventProcessing(payload);
        } catch (WebhookDuplicateException e) {
            // Counted as a webhook, never as a payment: a redelivery is not a second sale.
            metrics.webhook(Source.CARD, WebhookOutcome.DUPLICATE);
            RETRY.remove();
            throw e;
        }

        try {
            WebhookResult result = switch (payload.getEventType()) {
                case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> handleSuccessfulCheckout(payload, eventRecord);
                case "charge.refunded" -> handleChargeRefunded(payload, eventRecord);
                case "charge.dispute.created" -> handleChargeDisputed(payload, eventRecord);
                default -> handleIgnoredEvent(payload, eventRecord);
            };
            metrics.webhook(Source.CARD, WebhookOutcome.PROCESSED);
            return result;
        } catch (WebhookDuplicateException e) {
            metrics.webhook(Source.CARD, WebhookOutcome.DUPLICATE);
            throw e;
        } catch (WebhookProcessingException e) {
            markFailed(eventRecord, e.getMessage());
            metrics.webhook(Source.CARD, WebhookOutcome.FAILED);
            throw e;
        } catch (Exception e) {
            markFailed(eventRecord, e.getMessage());
            metrics.webhook(Source.CARD, WebhookOutcome.FAILED);
            throw new WebhookProcessingException("Failed to process Stripe event " + payload.getEventId(), e);
        } finally {
            RETRY.remove();
            metrics.duration(Source.CARD, PaymentMetrics.Stage.WEBHOOK,
                    java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /**
     * Whether the event being handled on this thread was seen before and FAILED (review of #268).
     *
     * <p>A failure that throws leaves the event FAILED and Stripe retries it for up to three days.
     * The payment failed once; each retry is another failed WEBHOOK, not another failed payment.
     * Set by {@link #startEventProcessing}, cleared when {@link #handle} returns.
     */
    private static final ThreadLocal<Boolean> RETRY = new ThreadLocal<>();

    /** Count a failed card payment, once per event: never again on Stripe's retries of it. */
    private void recordFailed(StripeWebhookPayload payload, Reason reason) {
        if (!Boolean.TRUE.equals(RETRY.get())) {
            metrics.record(Source.CARD, Status.FAILED, reason, payload.getCurrency());
        }
    }

    /** The amount of an event, as a long, or null. */
    private static Long amountOf(StripeWebhookPayload payload) {
        return payload.getAmountTotal() == null ? null : payload.getAmountTotal().longValue();
    }

    private WebhookResult handleSuccessfulCheckout(StripeWebhookPayload payload,
                                                   ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        // A DIRECT charge is a stall selling a coupon, and this service has no
        // GatewayQuote for it — findPaymentReference below would throw before
        // any consequence ran. Branch first, on the account Stripe puts on the
        // event.
        if (isPurchase(payload)) {
            return handlePaidPurchase(payload, eventRecord);
        }

        StripePaymentReference paymentReference = findPaymentReference(payload);
        String quoteId = resolveQuoteId(payload, paymentReference);

        validateReference(payload, paymentReference);

        boolean paymentSettled = "paid".equalsIgnoreCase(payload.getPaymentStatus());

        if (!paymentSettled && "checkout.session.completed".equals(payload.getEventType())) {
            log.info("Checkout session completed with payment_status='{}' for quote_ref={}; deferring settlement to async_payment_succeeded",
                    payload.getPaymentStatus(), QuoteRef.of(quoteId));
            paymentReference.setStripeStatus(StringUtils.defaultIfBlank(payload.getPaymentStatus(), payload.getStatus()));
            paymentReference.setLivemode(payload.isLivemode());
            paymentReference.setLastEventId(payload.getEventId());
            paymentReference.setUpdatedAt(Instant.now());
            paymentReferenceRepository.save(paymentReference);
            markProcessed(eventRecord);
            return new WebhookResult(true, null, State.PENDING, Map.of("eventType", payload.getEventType(), "deferred", true));
        }

        GatewayQuote quote = getRequiredQuote(quoteId);
        GatewayPayment payment = getRequiredPayment(quoteId);

        validateAmountAndCurrency(payload, quote);

        if (!State.PAID.equals(quote.getState())) {
            quote.setState(State.PAID);
            quoteClient.updateQuote(quote);
        }

        payment.setState(State.PAID);
        payment.setPaidDate(Instant.now());
        payment.setPaymentId(resolvePaymentId(payload, paymentReference));
        payment.setWebhookProcessedAt(Instant.now());
        paymentClient.updatePayment(payment);

        paymentReference.setPaymentIntentId(resolvePaymentId(payload, paymentReference));
        paymentReference.setStripeStatus(StringUtils.defaultIfBlank(payload.getPaymentStatus(), payload.getStatus()));
        paymentReference.setLivemode(payload.isLivemode());
        paymentReference.setLastEventId(payload.getEventId());
        paymentReference.setUpdatedAt(Instant.now());
        paymentReferenceRepository.save(paymentReference);

        markProcessed(eventRecord);
        metrics.record(Source.CARD, Status.SUCCEEDED, Reason.NONE, payload.getCurrency(), amountOf(payload));
        return WebhookResult.success(payment.getPaymentId(), State.PAID);
    }

    private WebhookResult handleChargeRefunded(StripeWebhookPayload payload,
                                               ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        StripePaymentReference paymentReference = findPaymentReference(payload);
        paymentReference.setChargeId(defaultIfBlank(payload.getChargeId(), paymentReference.getChargeId()));
        paymentReference.setStripeStatus("refunded");
        metrics.record(Source.CARD, Status.REFUNDED, Reason.NONE, payload.getCurrency(),
                payload.getRefundedAmountMinor() != null ? Long.valueOf(payload.getRefundedAmountMinor()) : amountOf(payload));
        // Use amount_refunded (the actual refunded total), not the original charge amount.
        paymentReference.setRefundedAmountMinor(
                payload.getRefundedAmountMinor() != null ? payload.getRefundedAmountMinor() : payload.getAmountTotal());
        paymentReference.setLastEventId(payload.getEventId());
        paymentReference.setUpdatedAt(Instant.now());
        paymentReferenceRepository.save(paymentReference);
        markProcessed(eventRecord);
        return new WebhookResult(true, null, State.PAID, Map.of("eventType", payload.getEventType()));
    }

    private WebhookResult handleChargeDisputed(StripeWebhookPayload payload,
                                               ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        StripePaymentReference paymentReference = findPaymentReference(payload);
        paymentReference.setChargeId(defaultIfBlank(payload.getChargeId(), paymentReference.getChargeId()));
        paymentReference.setStripeStatus("disputed");
        metrics.record(Source.CARD, Status.DISPUTED, Reason.NONE, payload.getCurrency(), amountOf(payload));
        paymentReference.setDisputed(true);
        paymentReference.setLastEventId(payload.getEventId());
        paymentReference.setUpdatedAt(Instant.now());
        paymentReferenceRepository.save(paymentReference);
        markProcessed(eventRecord);
        return new WebhookResult(true, null, State.PAID, Map.of("eventType", payload.getEventType()));
    }

    private WebhookResult handleIgnoredEvent(StripeWebhookPayload payload,
                                             ProcessedStripeWebhookEvent eventRecord) {
        // Not acted on, but two of them are the card's unhappy endings and belong in the count.
        switch (payload.getEventType()) {
            case "checkout.session.async_payment_failed", "payment_intent.payment_failed" ->
                    recordFailed(payload, Reason.CARD_DECLINED);
            case "checkout.session.expired" ->
                    metrics.record(Source.CARD, Status.CANCELLED, Reason.EXPIRED, payload.getCurrency());
            default -> { }
        }
        markProcessed(eventRecord);
        return new WebhookResult(true, null, State.PENDING, Map.of("ignored", true, "eventType", payload.getEventType()));
    }

    private ProcessedStripeWebhookEvent startEventProcessing(StripeWebhookPayload payload)
            throws WebhookDuplicateException, WebhookProcessingException {
        Optional<ProcessedStripeWebhookEvent> existingEvent = processedEventRepository.findById(payload.getEventId());
        if (existingEvent.isPresent()) {
            ProcessedStripeWebhookEvent prior = existingEvent.get();
            StripeWebhookProcessingStatus status = prior.getProcessingStatus();
            if (status == StripeWebhookProcessingStatus.PROCESSED) {
                throw new WebhookDuplicateException("Stripe webhook already processed: " + payload.getEventId());
            }
            if (status == StripeWebhookProcessingStatus.PROCESSING) {
                // Card atomicity audit S5b. PROCESSING is committed BEFORE the work, so a
                // process that dies mid-event leaves it behind. Answering that with "duplicate"
                // (200) made Stripe stop retrying a paid purchase that was never recorded: a
                // LOSS. A PROCESSING row older than the work could possibly take is a crash, and
                // is taken over. A fresh one may still be running on another replica, so the
                // delivery is refused (non-2xx) and Stripe comes back later, rather than being
                // told the event is done when nobody knows that yet.
                Instant started = prior.getProcessingStartedAt();
                if (started != null && started.isAfter(Instant.now().minus(processingStaleAfter))) {
                    throw new WebhookProcessingException("Stripe webhook " + payload.getEventId()
                            + " is being processed elsewhere; retry later");
                }
                log.warn("Stripe webhook {} was left PROCESSING since {}; taking it over",
                        payload.getEventId(), started);
            }
        }

        // Present here means FAILED (or RECEIVED): this delivery is a retry of a known event.
        RETRY.set(existingEvent.isPresent());
        ProcessedStripeWebhookEvent event = existingEvent.orElseGet(ProcessedStripeWebhookEvent::new);
        event.setEventId(payload.getEventId());
        event.setEventType(payload.getEventType());
        event.setPayloadHash(hashPayload(payload.getRawPayload()));
        event.setLivemode(payload.isLivemode());
        event.setReceivedAt(defaultIfNull(event.getReceivedAt(), payload.getEventTimestamp()));
        event.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        event.setProcessingStartedAt(Instant.now());
        event.setLastError(null);
        try {
            return processedEventRepository.save(event);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new WebhookDuplicateException("Concurrent Stripe webhook processing detected: " + payload.getEventId());
        }
    }

    private void markProcessed(ProcessedStripeWebhookEvent eventRecord) {
        eventRecord.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        eventRecord.setProcessedAt(Instant.now());
        eventRecord.setLastError(null);
        processedEventRepository.save(eventRecord);
    }

    private void markFailed(ProcessedStripeWebhookEvent eventRecord, String errorMessage) {
        eventRecord.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
        eventRecord.setLastError(StringUtils.abbreviate(errorMessage, 1024));
        processedEventRepository.save(eventRecord);
    }

    /**
     * Is this a stall selling a coupon, rather than this service selling a mint
     * quote?
     *
     * <p>The account is the discriminator, and it is a reliable one: a purchase
     * is always a direct charge (see the gateway module's
     * {@code createPurchaseSession}), and a mint quote is always a platform
     * charge, so only a purchase carries an account on its event.
     */
    private boolean isPurchase(StripeWebhookPayload payload) {
        return StringUtils.isNotBlank(payload.getConnectedAccountId());
    }

    /**
     * A stall's coupon was paid for. Hand it on, and record nothing here.
     *
     * <p>This service's job ends at "the money moved". Issuance belongs to a
     * listener, because minting needs custody of Nostr issuing keys and a
     * payments adapter should not acquire that.
     *
     * <p><b>No listener is a refusal, not a shrug.</b> A purchase reaching a
     * deployment that cannot act on it means a customer has paid and nobody
     * recorded the debt. Failing the event keeps it unprocessed, so Stripe
     * retries and the payment stays visible as unhandled rather than being
     * marked done and forgotten.
     */
    private WebhookResult handlePaidPurchase(StripeWebhookPayload payload,
                                             ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        if (!"paid".equalsIgnoreCase(payload.getPaymentStatus())
                && "checkout.session.completed".equals(payload.getEventType())) {
            // Same deferral the mint path makes: an unpaid completed session is
            // an async method still in flight, and issuing against it would be
            // giving a coupon away before the money exists.
            log.info("Purchase checkout completed with payment_status='{}' on account={}; deferring to async_payment_succeeded",
                    payload.getPaymentStatus(), payload.getConnectedAccountId());
            markProcessed(eventRecord);
            return new WebhookResult(true, null, State.PENDING,
                    Map.of("eventType", payload.getEventType(), "purchase", true, "deferred", true));
        }

        if (purchaseListener == null) {
            recordFailed(payload, Reason.NO_LISTENER);
            throw new WebhookProcessingException(
                    "Paid coupon purchase on account " + payload.getConnectedAccountId()
                            + " but no StripePurchaseListener is configured; refusing to mark it processed");
        }

        try {
            purchaseListener.onPurchasePaid(new StripePurchaseListener.PaidPurchase(
                    payload.getEventId(),
                    payload.getCheckoutSessionId(),
                    payload.getPaymentIntentId(),
                    payload.getConnectedAccountId(),
                    payload.getAmountTotal() == null ? null : payload.getAmountTotal().longValue(),
                    payload.getCurrency(),
                    payload.getMetadata() == null ? Map.of() : payload.getMetadata(),
                    payload.isLivemode()));
        } catch (Exception e) {
            // Deliberately not marked processed. The obligation was not
            // recorded, so the customer has paid for nothing until Stripe
            // retries — losing that quietly is the one outcome ADR 0010 rules
            // out.
            throw new WebhookProcessingException(
                    "Purchase listener refused event " + payload.getEventId(), e);
        }

        markProcessed(eventRecord);
        // Counted only once the debt is recorded. A redelivery never reaches here: it was
        // turned away as a duplicate by startEventProcessing.
        metrics.record(Source.CARD, Status.SUCCEEDED, Reason.NONE, payload.getCurrency(), amountOf(payload));
        return new WebhookResult(true, payload.getPaymentIntentId(), State.PAID,
                Map.of("eventType", payload.getEventType(), "purchase", true));
    }

    /** Session metadata, as a plain map. Empty rather than null when absent. */
    private Map<String, String> readMetadata(JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        metadata.fields().forEachRemaining(entry -> {
            String value = optionalText(entry.getValue());
            if (value != null) {
                values.put(entry.getKey(), value);
            }
        });
        return Map.copyOf(values);
    }

    private StripePaymentReference findPaymentReference(StripeWebhookPayload payload) throws WebhookProcessingException {
        return paymentReferenceRepository.findByCheckoutSessionId(payload.getCheckoutSessionId())
                .or(() -> paymentReferenceRepository.findByPaymentIntentId(payload.getPaymentIntentId()))
                .or(() -> paymentReferenceRepository.findByChargeId(payload.getChargeId()))
                .or(() -> paymentReferenceRepository.findByQuoteId(payload.getQuoteId()))
                .orElseThrow(() -> new WebhookProcessingException("Stripe payment reference not found for event " + payload.getEventId()));
    }

    private void validateAmountAndCurrency(StripeWebhookPayload payload, GatewayQuote quote) throws WebhookProcessingException {
        if (payload.getAmountTotal() != null && !payload.getAmountTotal().equals(quote.getAmount())) {
            recordFailed(payload, Reason.AMOUNT_MISMATCH);
            throw new WebhookProcessingException("Stripe amount mismatch for quote_ref=" + QuoteRef.of(quote.getQuoteId()));
        }
        if (StringUtils.isNotBlank(payload.getCurrency()) && !payload.getCurrency().equalsIgnoreCase(quote.getUnit())) {
            recordFailed(payload, Reason.CURRENCY_MISMATCH);
            throw new WebhookProcessingException("Stripe currency mismatch for quote_ref=" + QuoteRef.of(quote.getQuoteId()));
        }
    }

    private void validateReference(StripeWebhookPayload payload, StripePaymentReference paymentReference) throws WebhookProcessingException {
        if (StringUtils.isNotBlank(payload.getCheckoutSessionId())
                && !payload.getCheckoutSessionId().equals(paymentReference.getCheckoutSessionId())) {
            throw new WebhookProcessingException("Stripe checkout session mismatch for quote_ref=" + QuoteRef.of(paymentReference.getQuoteId()));
        }
    }

    private String resolveQuoteId(StripeWebhookPayload payload, StripePaymentReference paymentReference) {
        return StringUtils.defaultIfBlank(payload.getQuoteId(), paymentReference.getQuoteId());
    }

    private String resolvePaymentId(StripeWebhookPayload payload, StripePaymentReference paymentReference) {
        return defaultIfBlank(payload.getPaymentIntentId(), paymentReference.getPaymentIntentId());
    }

    private GatewayQuote getRequiredQuote(String quoteId) throws WebhookProcessingException {
        try {
            GatewayQuote quote = quoteClient.getByEntityId(quoteId);
            if (quote == null) {
                throw new WebhookProcessingException("Quote not found: " + QuoteRef.of(quoteId));
            }
            return quote;
        } catch (HttpClientErrorException e) {
            throw new WebhookProcessingException("Quote not found: " + QuoteRef.of(quoteId), e);
        }
    }

    private GatewayPayment getRequiredPayment(String quoteId) throws WebhookProcessingException {
        try {
            GatewayPayment payment = paymentClient.getByQuoteId(quoteId);
            if (payment == null) {
                throw new WebhookProcessingException("Payment not found for quote_ref=" + QuoteRef.of(quoteId));
            }
            return payment;
        } catch (HttpClientErrorException e) {
            throw new WebhookProcessingException("Payment not found for quote_ref=" + QuoteRef.of(quoteId), e);
        }
    }

    private void ensureDependencies() throws WebhookProcessingException {
        if (processedEventRepository == null) {
            throw new WebhookProcessingException("Stripe webhook handler requires ProcessedStripeWebhookEventRepository");
        }
        if (paymentReferenceRepository == null) {
            throw new WebhookProcessingException("Stripe webhook handler requires StripePaymentReferenceRepository");
        }
        if (quoteClient == null || paymentClient == null) {
            throw new WebhookProcessingException("Stripe webhook handler requires quote and payment clients");
        }
    }

    private String readBody(HttpServletRequest request) throws WebhookParseException {
        try {
            return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WebhookParseException("Failed to read Stripe webhook body", e);
        }
    }

    private String requiredText(JsonNode node, String fieldName) throws WebhookParseException {
        String value = optionalText(node.path(fieldName));
        if (StringUtils.isBlank(value)) {
            throw new WebhookParseException("Missing required Stripe webhook field: " + fieldName);
        }
        return value;
    }

    private String optionalText(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private String resolveText(JsonNode node, String fieldName) {
        return optionalText(node.path(fieldName));
    }

    private String resolveCheckoutSessionId(String eventType, JsonNode eventObject) {
        return switch (eventType) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> optionalText(eventObject.path("id"));
            default -> optionalText(eventObject.path("checkout_session"));
        };
    }

    private String resolveChargeId(String eventType, JsonNode eventObject) {
        return switch (eventType) {
            case "charge.refunded", "charge.dispute.created" -> optionalText(eventObject.path("id"));
            default -> optionalText(eventObject.path("latest_charge"));
        };
    }

    private Integer resolveAmount(JsonNode eventObject) {
        if (eventObject.hasNonNull("amount_total")) {
            return eventObject.get("amount_total").asInt();
        }
        if (eventObject.hasNonNull("amount")) {
            return eventObject.get("amount").asInt();
        }
        return null;
    }

    /**
     * Refunded total for charge.refunded events. Stripe's charge object carries
     * both {@code amount} (the original charge) and {@code amount_refunded} (the
     * cumulative refunded total). Use the latter so partial refunds record the
     * actual refunded amount rather than the full charge.
     */
    private Integer resolveRefundedAmount(JsonNode eventObject) {
        if (eventObject.hasNonNull("amount_refunded")) {
            return eventObject.get("amount_refunded").asInt();
        }
        return null;
    }

    private String hashPayload(String rawPayload) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte currentByte : digest) {
                builder.append(String.format("%02x", currentByte));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash Stripe webhook payload", e);
        }
    }

    private static final long DEFAULT_TOLERANCE_SECONDS = 300L;

    private static long parseTolerance(String toleranceValue) {
        if (StringUtils.isBlank(toleranceValue)) {
            return DEFAULT_TOLERANCE_SECONDS;
        }
        try {
            return Long.parseLong(toleranceValue);
        } catch (NumberFormatException e) {
            log.warn("Invalid STRIPE_WEBHOOK_TOLERANCE_SECONDS value '{}', using default {}s",
                    toleranceValue, DEFAULT_TOLERANCE_SECONDS);
            return DEFAULT_TOLERANCE_SECONDS;
        }
    }

    private static <T> T defaultIfNull(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value;
    }
}
