package xyz.tcheeric.payment.adapter.paypal.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.WebhookOutcome;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalMoney;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.webhook.recurring.PaypalRecurringPlanService;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalSignatureException;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.PaypalCaptureException;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

/**
 * PayPal's payments webhook, at {@code /webhook/paypal}: {@code StripeWebhookHandler}'s coupon
 * purchase half, for PayPal (ADR 0002). There is no mint-quote half: PayPal sells coupons only.
 *
 * <p>The same lifecycle on {@code processed_paypal_webhook_event}: PROCESSING is committed before the
 * work, a fresh PROCESSING mark is refused (non-2xx, PayPal redelivers), a stale one is taken over,
 * PROCESSED answers duplicates, and a failure marks FAILED and answers 5xx so PayPal tries again.
 *
 * <p>No ServiceLoader stub, unlike Stripe's: without the webhook id nothing can be verified, so
 * with PayPal off {@code /webhook/paypal} has no handler at all rather than one that fails.
 *
 * <p>Every purchase is found by this service's own {@code paypal_checkout} row and recorded through
 * {@link PaypalCaptureService}; refunds and disputes reach the same {@link StripePurchaseListener}
 * the Stripe webhook calls, so a PayPal refund does to its row exactly what a Stripe refund does.
 */
@Slf4j
public class PaypalWebhookHandler implements WebhookHandler<PaypalWebhookPayload> {

    public static final String PAYMENT_TYPE = "paypal";

    static final Duration DEFAULT_PROCESSING_STALE_AFTER = Duration.ofMinutes(2);

    /** A dispute ended this way leaves the seller the money: Stripe's {@code won}. */
    static final Set<String> SELLER_KEPT = Set.of("RESOLVED_SELLER_FAVOUR", "CANCELED_BY_BUYER");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaypalWebhookVerifier verifier;
    private final ProcessedPaypalWebhookEventRepository processedEvents;
    private final PaypalCheckoutRepository checkouts;
    private final StripePurchaseRepository purchases;
    private final PaypalCaptureService captures;
    private final StripePurchaseListener listener;
    private final String environment;
    private final boolean livemode;

    private PaymentMetrics metrics = new PaymentMetrics();
    private PaypalRecurringPlanService recurringPlans;
    private Duration processingStaleAfter = DEFAULT_PROCESSING_STALE_AFTER;

    /** Set by {@link #startEventProcessing}: this delivery retries an event seen before. */
    private static final ThreadLocal<Boolean> RETRY = new ThreadLocal<>();

    /**
     * @param environment this deployment's name, as stamped on every order's {@code custom_id}
     * @param livemode    whether this deployment talks to live PayPal: PayPal events carry no mode
     */
    public PaypalWebhookHandler(PaypalWebhookVerifier verifier, ProcessedPaypalWebhookEventRepository processedEvents,
            PaypalCheckoutRepository checkouts, StripePurchaseRepository purchases, PaypalCaptureService captures,
            StripePurchaseListener listener, String environment, boolean livemode) {
        this.verifier = verifier;
        this.processedEvents = processedEvents;
        this.checkouts = checkouts;
        this.purchases = purchases;
        this.captures = captures;
        this.listener = listener;
        this.environment = environment == null || environment.isBlank() ? null
                : environment.trim().toLowerCase(Locale.ROOT);
        this.livemode = livemode;
    }

    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry);
        this.metrics.preRegisterWebhooks(Source.CARD)
                .preRegister(Source.CARD, Status.SUCCEEDED, Status.FAILED, Status.REFUNDED, Status.DISPUTED);
    }

    void setProcessingStaleAfter(Duration processingStaleAfter) {
        this.processingStaleAfter = processingStaleAfter;
    }

    @Override
    public String getPaymentType() {
        return PAYMENT_TYPE;
    }

    @Override
    public PaypalWebhookPayload parsePayload(HttpServletRequest request) throws WebhookParseException {
        byte[] raw;
        try {
            raw = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new WebhookParseException("Failed to read PayPal webhook body", e);
        }
        metrics.webhook(Source.CARD, WebhookOutcome.RECEIVED);
        if (raw.length == 0) {
            throw new WebhookParseException("Empty PayPal webhook payload");
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (IOException e) {
            throw new WebhookParseException("Failed to parse PayPal webhook payload", e);
        }
        String id = text(root.path("id"));
        String type = text(root.path("event_type"));
        if (id == null || type == null) {
            throw new WebhookParseException("PayPal webhook payload has no id or event_type");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : List.of(PaypalWebhookVerifier.TRANSMISSION_ID, PaypalWebhookVerifier.TRANSMISSION_TIME,
                PaypalWebhookVerifier.TRANSMISSION_SIG, PaypalWebhookVerifier.CERT_URL,
                PaypalWebhookVerifier.AUTH_ALGO)) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return PaypalWebhookPayload.builder()
                .eventId(id)
                .eventType(type)
                .eventTimestamp(timestamp(root.path("create_time")))
                .rawBody(raw)
                .resource(root.path("resource"))
                .headers(PaypalWebhookPayload.caseInsensitive(headers))
                .build();
    }

    @Override
    public void validateSignature(PaypalWebhookPayload payload, HttpServletRequest request)
            throws WebhookSignatureException {
        try {
            verifier.verify(payload.getRawBody(), payload::header);
        } catch (PaypalSignatureException e) {
            metrics.webhook(Source.CARD, WebhookOutcome.SIGNATURE_REJECTED);
            throw new WebhookSignatureException("Invalid PayPal webhook signature: " + e.getMessage(), e);
        }
        metrics.webhook(Source.CARD, WebhookOutcome.VERIFIED);
    }

    @Override
    public WebhookResult handle(PaypalWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        long started = System.nanoTime();
        ProcessedPaypalWebhookEvent record;
        try {
            record = startEventProcessing(payload);
        } catch (WebhookDuplicateException e) {
            metrics.webhook(Source.CARD, WebhookOutcome.DUPLICATE);
            RETRY.remove();
            throw e;
        } catch (WebhookProcessingException e) {
            metrics.webhook(Source.CARD, WebhookOutcome.FAILED);
            RETRY.remove();
            throw e;
        }
        try {
            WebhookResult result = dispatch(payload, record);
            metrics.webhook(Source.CARD, WebhookOutcome.PROCESSED);
            return result;
        } catch (WebhookProcessingException e) {
            markFailed(record, e.getMessage());
            metrics.webhook(Source.CARD, WebhookOutcome.FAILED);
            throw e;
        } catch (Exception e) {
            markFailed(record, e.getMessage());
            metrics.webhook(Source.CARD, WebhookOutcome.FAILED);
            throw new WebhookProcessingException("Failed to process PayPal event " + payload.getEventId(), e);
        } finally {
            RETRY.remove();
            metrics.duration(Source.CARD, PaymentMetrics.Stage.WEBHOOK, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** Exhaustive over {@link PaypalPaymentEvent}; anything else is acknowledged and ignored. */
    private WebhookResult dispatch(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record)
            throws WebhookProcessingException {
        Optional<PaypalPaymentEvent> event = PaypalPaymentEvent.fromType(payload.getEventType());
        if (event.isEmpty()) {
            return acknowledge(payload, record);
        }
        JsonNode resource = payload.getResource();
        return switch (event.get()) {
            case CHECKOUT_ORDER_APPROVED -> orderApproved(payload, record, resource);
            case PAYMENT_CAPTURE_COMPLETED -> captureCompleted(payload, record, resource);
            case PAYMENT_CAPTURE_DECLINED -> captureDeclined(payload, record, resource);
            case PAYMENT_CAPTURE_REFUNDED -> refunded(payload, record, resource, false);
            case PAYMENT_CAPTURE_REVERSED -> refunded(payload, record, resource, true);
            case CUSTOMER_DISPUTE_CREATED -> disputeCreated(payload, record, resource);
            case CUSTOMER_DISPUTE_RESOLVED -> disputeResolved(payload, record, resource);
            case BILLING_SUBSCRIPTION_ACTIVATED -> subscriptionEvent(payload, record, plan -> plan.activated(text(resource.path("id"))));
            case BILLING_SUBSCRIPTION_PAYMENT_FAILED, BILLING_SUBSCRIPTION_SUSPENDED ->
                    subscriptionEvent(payload, record, plan -> plan.paymentFailed(text(resource.path("id"))));
            case BILLING_SUBSCRIPTION_CANCELLED, BILLING_SUBSCRIPTION_EXPIRED ->
                    subscriptionEvent(payload, record, plan -> plan.ended(text(resource.path("id"))));
            case PAYMENT_SALE_COMPLETED -> saleCompleted(payload, record, resource);
            case PAYMENT_SALE_REFUNDED -> saleReturned(payload, record, resource, false);
            case PAYMENT_SALE_REVERSED -> saleReturned(payload, record, resource, true);
        };
    }

    /**
     * Instalment plans are optional ({@code paypal.recurring.enabled}). Without them, a subscription event is somebody
     * else's and is acknowledged.
     */
    public void setRecurringPlans(PaypalRecurringPlanService recurringPlans) {
        this.recurringPlans = recurringPlans;
    }

    private WebhookResult subscriptionEvent(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            java.util.function.Consumer<PaypalRecurringPlanService> work) {
        if (recurringPlans != null) {
            work.accept(recurringPlans);
        }
        return acknowledge(payload, record);
    }

    /** An instalment was paid: one ledger row per sale, for the buyer on Imani's plan row. */
    private WebhookResult saleCompleted(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode resource) throws WebhookProcessingException {
        PaypalSale sale = PaypalSale.fromSale(resource);
        if (recurringPlans == null) {
            return acknowledge(payload, record);
        }
        boolean ours;
        try {
            ours = recurringPlans.salePaid(sale);
        } catch (RuntimeException e) {
            // Not marked processed: the instalment was not recorded, so PayPal must come back with it.
            throw new WebhookProcessingException("Could not record PayPal instalment " + sale.id(), e);
        }
        if (!ours) {
            log.info("PayPal sale {} pays no plan of ours; acknowledged", sale.id());
            return acknowledge(payload, record);
        }
        markProcessed(record);
        return purchaseResult(payload, sale.id());
    }

    /**
     * A refund or reversal of an instalment. The ledger row is found by its sale id (its processor reference), and
     * reversed exactly as a capture refund is; the plan is then flagged for a person.
     */
    private WebhookResult saleReturned(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode resource, boolean reversal) throws WebhookProcessingException {
        String saleId = reversal ? text(resource.path("id")) : text(resource.path("sale_id"));
        Optional<StripePurchase> row = saleId == null ? Optional.empty()
                : purchases.findByPaymentIntentId(saleId).filter(StripePurchase::isPaypal);
        if (saleId == null) {
            return acknowledge(payload, record);
        }
        // Reversed even when no row exists yet: a refund can overtake its sale, and the listener records it either
        // way, exactly as for a capture refund.
        Long thisRefund = saleMinor(resource.path("amount"));
        Long total = saleMinor(resource.path("total_refunded_amount"));
        Long rowAmount = row.map(StripePurchase::getAmountMinor).orElse(null);
        Long refundedTotal = reversal ? (rowAmount != null ? rowAmount : thisRefund) : total != null ? total : thisRefund;
        reverse(payload, new StripePurchaseListener.ReversedPurchase(payload.getEventId(), saleId,
                text(resource.path("id")), row.map(StripePurchase::getConnectedAccountId).orElse(null), refundedTotal,
                rowAmount != null ? rowAmount : thisRefund, false));
        if (recurringPlans != null) {
            recurringPlans.instalmentReturned(saleId);
        }
        markProcessed(record);
        metrics.record(Source.CARD, Status.REFUNDED, Reason.NONE,
                lower(row.map(StripePurchase::getCurrency).orElse(null)), thisRefund);
        return purchaseResult(payload, saleId);
    }

    /** A v1 amount: {@code {total|value, currency}}, or a v2 one ({@code value, currency_code}). */
    private static Long saleMinor(JsonNode money) {
        String currency = text(money.path("currency"));
        String value = text(money.path("total")) != null ? text(money.path("total")) : text(money.path("value"));
        if (currency == null || value == null) {
            return minor(money);
        }
        try {
            return PaypalMoney.toMinor(value, currency);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Approved, not paid: capture now, so the money moves while the buyer is still here. */
    private WebhookResult orderApproved(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode order) throws WebhookProcessingException {
        String orderId = text(order.path("id"));
        Optional<PaypalCheckout> checkout = orderId == null ? Optional.empty() : checkouts.findByOrderId(orderId);
        if (checkout.isEmpty()) {
            log.info("PayPal order {} approved but not created here; acknowledged", orderId);
            return acknowledge(payload, record);
        }
        Outcome outcome = capture(() -> captures.capture(checkout.get(), false), payload);
        if (outcome == Outcome.UNAVAILABLE) {
            throw new WebhookProcessingException("PayPal unavailable capturing order " + orderId + "; retry later");
        }
        return finish(payload, record, outcome, checkout.get().getCaptureId());
    }

    /** Paid means owed. */
    private WebhookResult captureCompleted(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode resource) throws WebhookProcessingException {
        PaypalCapture capture = PaypalCapture.from(resource, null);
        Optional<PaypalCheckout> checkout = findCheckout(capture);
        if (checkout.isEmpty()) {
            String named = environmentOf(capture.customId());
            if (named != null && environment != null && !named.equals(environment)) {
                log.warn("purchase_foreign_environment PayPal capture {} names environment '{}', this is '{}':"
                        + " not recorded", capture.id(), named, environment);
                return acknowledge(payload, record);
            }
            boolean recorded = capture(() -> captures.recordOrphan(capture, livemode) ? Outcome.RECORDED
                    : Outcome.DECLINED, payload) == Outcome.RECORDED;
            if (!recorded) {
                log.info("PayPal capture {} pays no seller of ours; acknowledged", capture.id());
                return acknowledge(payload, record);
            }
            return finish(payload, record, Outcome.RECORDED, capture.id());
        }
        Outcome outcome = capture(() -> captures.settleCapture(checkout.get(), capture, false), payload);
        return finish(payload, record, outcome, capture.id());
    }

    private WebhookResult captureDeclined(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode resource) throws WebhookProcessingException {
        PaypalCapture capture = PaypalCapture.from(resource, null);
        findCheckout(capture).ifPresent(checkout -> captures.settleCapture(checkout, capture, false));
        recordFailed(capture.currency());
        return acknowledge(payload, record);
    }

    /**
     * A refund (or a reversal, which PayPal reports with a refund object). The capture is the
     * refund's {@code up} link. {@code total_refunded_amount} is cumulative, like Stripe's
     * {@code amount_refunded}; a reversal takes back the whole capture.
     */
    private WebhookResult refunded(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode refund, boolean reversal) throws WebhookProcessingException {
        String captureId = captureOfRefund(refund);
        Optional<StripePurchase> row = captureId == null ? Optional.empty()
                : purchases.findByPaymentIntentId(captureId).filter(StripePurchase::isPaypal);
        String currency = text(refund.path("amount").path("currency_code"));
        Long thisRefund = minor(refund.path("amount"));
        Long total = minor(refund.path("seller_payable_breakdown").path("total_refunded_amount"));
        Long refundedTotal = reversal ? row.map(StripePurchase::getAmountMinor).orElse(null)
                : total != null ? total : thisRefund;
        reverse(payload, new StripePurchaseListener.ReversedPurchase(payload.getEventId(), captureId,
                text(refund.path("id")), row.map(StripePurchase::getConnectedAccountId).orElse(null),
                refundedTotal, row.map(StripePurchase::getAmountMinor).orElse(thisRefund), false));
        markProcessed(record);
        metrics.record(Source.CARD, Status.REFUNDED, Reason.NONE, lower(currency), thisRefund);
        return purchaseResult(payload, captureId);
    }

    private WebhookResult disputeCreated(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode dispute) throws WebhookProcessingException {
        Long amount = minor(dispute.path("dispute_amount"));
        String currency = text(dispute.path("dispute_amount").path("currency_code"));
        String captureId = null;
        for (String disputed : disputedCaptures(dispute)) {
            captureId = disputed;
            reverse(payload, new StripePurchaseListener.ReversedPurchase(payload.getEventId(), disputed,
                    text(dispute.path("dispute_id")), sellerOf(dispute), null, amount, true));
            if (recurringPlans != null) {
                // A disputed instalment (its sale id is the disputed transaction) flags its plan, as a refund does.
                recurringPlans.instalmentReturned(disputed);
            }
        }
        markProcessed(record);
        metrics.record(Source.CARD, Status.DISPUTED, Reason.NONE, lower(currency), amount);
        return purchaseResult(payload, captureId);
    }

    private WebhookResult disputeResolved(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record,
            JsonNode dispute) throws WebhookProcessingException {
        String outcome = text(dispute.path("dispute_outcome").path("outcome_code"));
        boolean won = outcome != null && SELLER_KEPT.contains(outcome.toUpperCase(Locale.ROOT));
        String captureId = null;
        for (String disputed : disputedCaptures(dispute)) {
            captureId = disputed;
            try {
                listener.onPurchaseDisputeClosed(payload.getEventId(), disputed, won);
            } catch (Exception e) {
                throw new WebhookProcessingException(
                        "Purchase listener could not record " + payload.getEventType() + " " + payload.getEventId(), e);
            }
        }
        markProcessed(record);
        return new WebhookResult(true, null, State.PAID,
                Map.of("eventType", payload.getEventType(), "purchase", true, "won", won,
                        "reference", captureId == null ? "" : captureId));
    }

    private WebhookResult finish(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record, Outcome outcome,
            String captureId) {
        markProcessed(record);
        if (outcome == Outcome.RECORDED && PaypalPaymentEvent.PAYMENT_CAPTURE_COMPLETED.type().equals(payload.getEventType())
                && !Boolean.TRUE.equals(RETRY.get())) {
            metrics.record(Source.CARD, Status.SUCCEEDED, Reason.NONE, null);
        }
        // paymentId stays null: a coupon purchase has no GatewayPayment row for the dispatcher to move.
        return new WebhookResult(true, null, outcome == Outcome.RECORDED ? State.PAID : State.PENDING,
                Map.of("eventType", payload.getEventType(), "purchase", true, "outcome", outcome.name(),
                        "reference", captureId == null ? "" : captureId));
    }

    private WebhookResult purchaseResult(PaypalWebhookPayload payload, String captureId) {
        return new WebhookResult(true, null, State.PAID, Map.of("eventType", payload.getEventType(), "purchase", true,
                "reference", captureId == null ? "" : captureId));
    }

    private void reverse(PaypalWebhookPayload payload, StripePurchaseListener.ReversedPurchase reversal)
            throws WebhookProcessingException {
        try {
            listener.onPurchaseReversed(reversal);
        } catch (Exception e) {
            throw new WebhookProcessingException(
                    "Purchase listener could not record " + payload.getEventType() + " " + payload.getEventId(), e);
        }
    }

    private Outcome capture(java.util.function.Supplier<Outcome> work, PaypalWebhookPayload payload)
            throws WebhookProcessingException {
        try {
            return work.get();
        } catch (PaypalCaptureException e) {
            // Not marked processed: the debt was not recorded, so PayPal must come back with it.
            throw new WebhookProcessingException("Purchase listener refused PayPal event " + payload.getEventId(), e);
        }
    }

    private Optional<PaypalCheckout> findCheckout(PaypalCapture capture) {
        Optional<PaypalCheckout> byOrder = capture.orderId() == null ? Optional.empty()
                : checkouts.findByOrderId(capture.orderId());
        return byOrder.isPresent() || capture.id() == null ? byOrder : checkouts.findByCaptureId(capture.id());
    }

    private void recordFailed(String currency) {
        if (!Boolean.TRUE.equals(RETRY.get())) {
            metrics.record(Source.CARD, Status.FAILED, Reason.CARD_DECLINED, lower(currency));
        }
    }

    private WebhookResult acknowledge(PaypalWebhookPayload payload, ProcessedPaypalWebhookEvent record) {
        markProcessed(record);
        return new WebhookResult(true, null, State.PENDING, Map.of("ignored", true, "eventType", payload.getEventType()));
    }

    private ProcessedPaypalWebhookEvent startEventProcessing(PaypalWebhookPayload payload)
            throws WebhookDuplicateException, WebhookProcessingException {
        Optional<ProcessedPaypalWebhookEvent> existing = processedEvents.findById(payload.getEventId());
        if (existing.isPresent()) {
            ProcessedPaypalWebhookEvent prior = existing.get();
            if (prior.getProcessingStatus() == StripeWebhookProcessingStatus.PROCESSED) {
                throw new WebhookDuplicateException("PayPal webhook already processed: " + payload.getEventId());
            }
            if (prior.getProcessingStatus() == StripeWebhookProcessingStatus.PROCESSING) {
                // Card atomicity audit S5b, as Stripe's: a fresh mark may still be running elsewhere,
                // a stale one is a crash and is taken over. Never "duplicate" on a mark nobody finished.
                Instant began = prior.getProcessingStartedAt();
                if (began != null && began.isAfter(Instant.now().minus(processingStaleAfter))) {
                    throw new WebhookProcessingException("PayPal webhook " + payload.getEventId()
                            + " is being processed elsewhere; retry later");
                }
                log.warn("PayPal webhook {} was left PROCESSING since {}; taking it over", payload.getEventId(), began);
            }
        }
        RETRY.set(existing.isPresent());
        ProcessedPaypalWebhookEvent event = existing.orElseGet(ProcessedPaypalWebhookEvent::new);
        event.setEventId(payload.getEventId());
        event.setEventType(payload.getEventType());
        event.setPayloadHash(sha256(payload.getRawBody()));
        event.setLivemode(livemode);
        event.setReceivedAt(event.getReceivedAt() != null ? event.getReceivedAt() : payload.getEventTimestamp());
        event.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        event.setProcessingStartedAt(Instant.now());
        event.setLastError(null);
        try {
            return processedEvents.save(event);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new WebhookProcessingException("PayPal webhook " + payload.getEventId()
                    + " is being processed concurrently; retry later", e);
        }
    }

    private void markProcessed(ProcessedPaypalWebhookEvent record) {
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        record.setProcessedAt(Instant.now());
        record.setLastError(null);
        processedEvents.save(record);
    }

    private void markFailed(ProcessedPaypalWebhookEvent record, String message) {
        record.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
        record.setLastError(message == null ? null : message.substring(0, Math.min(1024, message.length())));
        processedEvents.save(record);
    }

    /** The deployment an order's {@code custom_id} ({@code <env>:<quote>}) names, or null. */
    static String environmentOf(String customId) {
        if (customId == null) {
            return null;
        }
        int colon = customId.indexOf(':');
        return colon <= 0 ? null : customId.substring(0, colon).trim().toLowerCase(Locale.ROOT);
    }

    /** The capture a refund belongs to: its {@code up} link, {@code /v2/payments/captures/<id>}. */
    static String captureOfRefund(JsonNode refund) {
        for (JsonNode link : refund.path("links")) {
            if ("up".equalsIgnoreCase(link.path("rel").asText())) {
                String href = link.path("href").asText("");
                int at = href.indexOf("/captures/");
                if (at >= 0) {
                    String rest = href.substring(at + "/captures/".length());
                    int end = rest.indexOf('/');
                    String id = end < 0 ? rest : rest.substring(0, end);
                    return id.isBlank() ? null : id;
                }
            }
        }
        return null;
    }

    private static List<String> disputedCaptures(JsonNode dispute) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode transaction : dispute.path("disputed_transactions")) {
            String id = text(transaction.path("seller_transaction_id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String sellerOf(JsonNode dispute) {
        return text(dispute.path("disputed_transactions").path(0).path("seller").path("merchant_id"));
    }

    private static Long minor(JsonNode money) {
        String currency = text(money.path("currency_code"));
        String value = text(money.path("value"));
        if (currency == null || value == null) {
            return null;
        }
        try {
            return PaypalMoney.toMinor(value, currency);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant timestamp(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lower(String currency) {
        return currency == null ? null : currency.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    static String utf8(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
