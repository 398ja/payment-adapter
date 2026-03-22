package xyz.tcheeric.payment.adapter.stripe.webhook;

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
                    .currency(optionalText(eventObject.path("currency")))
                    .livemode(root.path("livemode").asBoolean(false))
                    .status(optionalText(eventObject.path("status")))
                    .paymentStatus(optionalText(eventObject.path("payment_status")))
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
        signatureVerifier.verify(payload.getRawPayload(), request.getHeader("Stripe-Signature"));
    }

    @Override
    public WebhookResult handle(StripeWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        ensureDependencies();
        ProcessedStripeWebhookEvent eventRecord = startEventProcessing(payload);

        try {
            return switch (payload.getEventType()) {
                case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> handleSuccessfulCheckout(payload, eventRecord);
                case "charge.refunded" -> handleChargeRefunded(payload, eventRecord);
                case "charge.dispute.created" -> handleChargeDisputed(payload, eventRecord);
                default -> handleIgnoredEvent(payload, eventRecord);
            };
        } catch (WebhookDuplicateException e) {
            throw e;
        } catch (WebhookProcessingException e) {
            markFailed(eventRecord, e.getMessage());
            throw e;
        } catch (Exception e) {
            markFailed(eventRecord, e.getMessage());
            throw new WebhookProcessingException("Failed to process Stripe event " + payload.getEventId(), e);
        }
    }

    private WebhookResult handleSuccessfulCheckout(StripeWebhookPayload payload,
                                                   ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        StripePaymentReference paymentReference = findPaymentReference(payload);
        String quoteId = resolveQuoteId(payload, paymentReference);

        validateReference(payload, paymentReference);

        boolean paymentSettled = "paid".equalsIgnoreCase(payload.getPaymentStatus());

        if (!paymentSettled && "checkout.session.completed".equals(payload.getEventType())) {
            log.info("Checkout session completed with payment_status='{}' for quoteId={}; deferring settlement to async_payment_succeeded",
                    payload.getPaymentStatus(), quoteId);
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
        return WebhookResult.success(payment.getPaymentId(), State.PAID);
    }

    private WebhookResult handleChargeRefunded(StripeWebhookPayload payload,
                                               ProcessedStripeWebhookEvent eventRecord) throws WebhookProcessingException {
        StripePaymentReference paymentReference = findPaymentReference(payload);
        paymentReference.setChargeId(defaultIfBlank(payload.getChargeId(), paymentReference.getChargeId()));
        paymentReference.setStripeStatus("refunded");
        paymentReference.setRefundedAmountMinor(payload.getAmountTotal());
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
        paymentReference.setDisputed(true);
        paymentReference.setLastEventId(payload.getEventId());
        paymentReference.setUpdatedAt(Instant.now());
        paymentReferenceRepository.save(paymentReference);
        markProcessed(eventRecord);
        return new WebhookResult(true, null, State.PAID, Map.of("eventType", payload.getEventType()));
    }

    private WebhookResult handleIgnoredEvent(StripeWebhookPayload payload,
                                             ProcessedStripeWebhookEvent eventRecord) {
        markProcessed(eventRecord);
        return new WebhookResult(true, null, State.PENDING, Map.of("ignored", true, "eventType", payload.getEventType()));
    }

    private ProcessedStripeWebhookEvent startEventProcessing(StripeWebhookPayload payload) throws WebhookDuplicateException {
        Optional<ProcessedStripeWebhookEvent> existingEvent = processedEventRepository.findById(payload.getEventId());
        if (existingEvent.isPresent()) {
            StripeWebhookProcessingStatus status = existingEvent.get().getProcessingStatus();
            if (status == StripeWebhookProcessingStatus.PROCESSED
                    || status == StripeWebhookProcessingStatus.PROCESSING) {
                throw new WebhookDuplicateException("Stripe webhook already " + status.name().toLowerCase() + ": " + payload.getEventId());
            }
        }

        ProcessedStripeWebhookEvent event = existingEvent.orElseGet(ProcessedStripeWebhookEvent::new);
        event.setEventId(payload.getEventId());
        event.setEventType(payload.getEventType());
        event.setPayloadHash(hashPayload(payload.getRawPayload()));
        event.setLivemode(payload.isLivemode());
        event.setReceivedAt(defaultIfNull(event.getReceivedAt(), payload.getEventTimestamp()));
        event.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
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

    private StripePaymentReference findPaymentReference(StripeWebhookPayload payload) throws WebhookProcessingException {
        return paymentReferenceRepository.findByCheckoutSessionId(payload.getCheckoutSessionId())
                .or(() -> paymentReferenceRepository.findByPaymentIntentId(payload.getPaymentIntentId()))
                .or(() -> paymentReferenceRepository.findByChargeId(payload.getChargeId()))
                .or(() -> paymentReferenceRepository.findByQuoteId(payload.getQuoteId()))
                .orElseThrow(() -> new WebhookProcessingException("Stripe payment reference not found for event " + payload.getEventId()));
    }

    private void validateAmountAndCurrency(StripeWebhookPayload payload, GatewayQuote quote) throws WebhookProcessingException {
        if (payload.getAmountTotal() != null && !payload.getAmountTotal().equals(quote.getAmount())) {
            throw new WebhookProcessingException("Stripe amount mismatch for quoteId=" + quote.getQuoteId());
        }
        if (StringUtils.isNotBlank(payload.getCurrency()) && !payload.getCurrency().equalsIgnoreCase(quote.getUnit())) {
            throw new WebhookProcessingException("Stripe currency mismatch for quoteId=" + quote.getQuoteId());
        }
    }

    private void validateReference(StripeWebhookPayload payload, StripePaymentReference paymentReference) throws WebhookProcessingException {
        if (StringUtils.isNotBlank(payload.getCheckoutSessionId())
                && !payload.getCheckoutSessionId().equals(paymentReference.getCheckoutSessionId())) {
            throw new WebhookProcessingException("Stripe checkout session mismatch for quoteId=" + paymentReference.getQuoteId());
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
                throw new WebhookProcessingException("Quote not found: " + quoteId);
            }
            return quote;
        } catch (HttpClientErrorException e) {
            throw new WebhookProcessingException("Quote not found: " + quoteId, e);
        }
    }

    private GatewayPayment getRequiredPayment(String quoteId) throws WebhookProcessingException {
        try {
            GatewayPayment payment = paymentClient.getByQuoteId(quoteId);
            if (payment == null) {
                throw new WebhookProcessingException("Payment not found for quoteId=" + quoteId);
            }
            return payment;
        } catch (HttpClientErrorException e) {
            throw new WebhookProcessingException("Payment not found for quoteId=" + quoteId, e);
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
