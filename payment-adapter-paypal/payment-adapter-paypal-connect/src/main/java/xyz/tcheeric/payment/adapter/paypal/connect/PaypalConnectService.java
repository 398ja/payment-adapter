package xyz.tcheeric.payment.adapter.paypal.connect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.ConnectEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.paypal.connect.config.PaypalConnectProperties;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectExceptionCode;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectAccountResponse;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectWebhookResponse;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalSignatureException;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;

/**
 * A stall's PayPal seller account, onboarded through Partner Referrals: {@code StripeConnectService}
 * for PayPal (ADR 0002). Same endpoints, same response, same webhook bookkeeping.
 *
 * <h2>How a seller is found</h2>
 *
 * <p>Stripe hands back an account id the moment one is created. PayPal does not: a referral names a
 * {@code tracking_id} of ours, and the seller's merchant id exists only once they finish PayPal's
 * flow. So each stall gets one tracking id, stored before the seller ever sees PayPal, and every
 * later read resolves it: {@code MERCHANT.ONBOARDING.COMPLETED} carries both, and a status read
 * asks PayPal for the merchant behind it. A seller status naming another tracking id is an
 * ownership mismatch, the counterpart of Stripe's {@code merchant_pubkey} metadata check.
 */
@Slf4j
public class PaypalConnectService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final PaypalConnectProperties connectProperties;
    private final PaypalGatewayProperties gatewayProperties;
    private final PaypalPartnerClient partner;
    private final PaypalWebhookVerifier webhookVerifier;
    private final ObjectMapper objectMapper;
    private final ConnectedPaypalAccountRepository accounts;
    private final ProcessedPaypalWebhookEventRepository processedEvents;
    private final TransactionTemplate requiresNewTx;

    private PaymentMetrics metrics = new PaymentMetrics();
    private RecurringPlanRepository recurringPlans;

    /** Instalment plans on PayPal (ADR 0003): guards disconnect and orphans plans on a revoked consent. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecurringPlans(RecurringPlanRepository recurringPlans) {
        this.recurringPlans = recurringPlans;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry).preRegisterConnect();
    }

    public PaypalConnectService(
            PaypalConnectProperties connectProperties,
            PaypalGatewayProperties gatewayProperties,
            PaypalPartnerClient partner,
            PaypalWebhookVerifier webhookVerifier,
            ObjectMapper objectMapper,
            ConnectedPaypalAccountRepository accounts,
            ProcessedPaypalWebhookEventRepository processedEvents,
            PlatformTransactionManager transactionManager) {
        this.connectProperties = connectProperties;
        this.gatewayProperties = gatewayProperties;
        this.partner = partner;
        this.webhookVerifier = webhookVerifier;
        this.objectMapper = objectMapper;
        this.accounts = accounts;
        this.processedEvents = processedEvents;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public PaypalConnectAccountResponse createOrResume(String merchantPubkey, String returnUrl) {
        requireEnabled();
        ConnectedPaypalAccount account = accounts.findByMerchantPubkey(merchantPubkey)
                .map(this::refreshExisting)
                .orElseGet(() -> startAccount(merchantPubkey));
        String onboardingUrl = needsOnboardingLink(account)
                ? partner.createReferral(account.getTrackingId(), resolveReturnUrl(returnUrl))
                : null;
        return toResponse(account, onboardingUrl);
    }

    @Transactional
    public PaypalConnectAccountResponse refresh(String merchantPubkey, String returnUrl) {
        requireEnabled();
        ConnectedPaypalAccount account = accounts.findByMerchantPubkey(merchantPubkey)
                .map(this::refreshExisting)
                .orElseThrow(() -> new PaypalConnectException(PaypalConnectExceptionCode.ACCOUNT_NOT_FOUND,
                        org.springframework.http.HttpStatus.BAD_REQUEST, "No PayPal seller account exists for merchant"));
        String onboardingUrl = partner.createReferral(account.getTrackingId(), resolveReturnUrl(returnUrl));
        return toResponse(account, onboardingUrl);
    }

    @Transactional
    public PaypalConnectAccountResponse getStatus(String merchantPubkey) {
        requireEnabled();
        return accounts.findByMerchantPubkey(merchantPubkey)
                .map(existing -> toResponse(refreshExisting(existing), null))
                .orElseGet(() -> new PaypalConnectAccountResponse(
                        merchantPubkey, null, null, "not_connected", false, false, false, false, null, List.of()));
    }

    @Transactional
    public void disconnect(String merchantPubkey) {
        requireEnabled();
        if (recurringPlans != null) {
            accounts.findByMerchantPubkey(merchantPubkey)
                    .map(ConnectedPaypalAccount::getPaypalMerchantId)
                    .filter(merchantId -> recurringPlans.existsByConnectedAccountIdAndStatusIn(
                            merchantId, RecurringPlan.CHARGING))
                    .ifPresent(merchantId -> {
                        throw PaypalConnectException.activePlans();
                    });
        }
        accounts.deleteByMerchantPubkey(merchantPubkey);
    }

    /**
     * @param rawBody the delivery's bytes exactly as received: PayPal signs their CRC32
     * @param headers a case-insensitive header lookup
     */
    @Transactional
    public PaypalConnectWebhookResponse handleWebhook(byte[] rawBody, Function<String, String> headers) {
        requireEnabled();
        try {
            webhookVerifier.verify(rawBody, headers);
        } catch (PaypalSignatureException e) {
            throw PaypalConnectException.badSignature("Invalid PayPal webhook signature: " + e.getMessage(), e);
        }
        String payload = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw PaypalConnectException.badSignature("PayPal webhook body is not JSON", e);
        }
        String eventId = event.path("id").asText(null);
        String eventType = event.path("event_type").asText("");
        if (eventId == null || eventId.isBlank()) {
            throw PaypalConnectException.badSignature("PayPal webhook has no event id", null);
        }

        String currentHash = sha256(payload);
        Optional<ProcessedPaypalWebhookEvent> existing = processedEvents.findById(eventId);
        if (existing.isPresent() && existing.get().getProcessingStatus() == StripeWebhookProcessingStatus.PROCESSED) {
            if (!currentHash.equals(existing.get().getPayloadHash())) {
                log.warn("PayPal webhook {} redelivered with different payload hash (stored={}, received={})",
                        eventId, existing.get().getPayloadHash(), currentHash);
            }
            return new PaypalConnectWebhookResponse(eventId, "duplicate");
        }

        ProcessedPaypalWebhookEvent record = existing.orElseGet(() -> newRecord(eventId, eventType, currentHash));
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        record.setProcessingStartedAt(Instant.now());
        record.setLastError(null);
        processedEvents.save(record);
        try {
            Optional<PaypalConnectEvent> handled = PaypalConnectEvent.fromType(eventType);
            if (handled.isEmpty()) {
                markProcessed(record);
                return new PaypalConnectWebhookResponse(eventId, "ignored");
            }
            JsonNode resource = event.path("resource");
            Runnable handler = switch (handled.get()) {
                case MERCHANT_ONBOARDING_COMPLETED, SELLER_EMAIL_CONFIRMED, CAPABILITY_UPDATED ->
                        () -> handleSellerChanged(resource);
                case PARTNER_CONSENT_REVOKED -> () -> handleConsentRevoked(resource);
            };
            handler.run();
            markProcessed(record);
            return new PaypalConnectWebhookResponse(eventId, "processed");
        } catch (RuntimeException e) {
            markFailed(eventId, eventType, currentHash, e.getMessage());
            throw e;
        }
    }

    private ConnectedPaypalAccount startAccount(String merchantPubkey) {
        ConnectedPaypalAccount account = new ConnectedPaypalAccount();
        account.setMerchantPubkey(merchantPubkey);
        // Ours, opaque and unguessable: what PayPal hands back to say which stall a seller is.
        account.setTrackingId("stall-" + UUID.randomUUID());
        account.setDefaultCurrency(gatewayProperties.getDefaultCurrency());
        account.setRequirementsDue(writeRequirements(List.of()));
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        metrics.connect(ConnectEvent.ONBOARDING_STARTED);
        return accounts.save(account);
    }

    /** Asks PayPal about the seller behind this stall, learning the merchant id first if needed. */
    private ConnectedPaypalAccount refreshExisting(ConnectedPaypalAccount existing) {
        String merchantId = existing.getPaypalMerchantId();
        if (merchantId == null) {
            Optional<String> found = partner.findMerchantId(existing.getTrackingId());
            if (found.isEmpty()) {
                return existing;
            }
            merchantId = found.get();
        }
        try {
            PaypalSellerStatus status = partner.sellerStatus(merchantId);
            ensureOwnership(status, existing);
            return saveStatus(existing, status);
        } catch (PaypalConnectException e) {
            if (e.getCode() == PaypalConnectExceptionCode.ACCOUNT_NOT_FOUND) {
                // PayPal no longer knows this seller for us: back to the start, keeping the tracking id.
                return saveStatus(existing, null);
            }
            throw e;
        }
    }

    private void handleSellerChanged(JsonNode resource) {
        String merchantId = text(resource.path("merchant_id"));
        String trackingId = text(resource.path("tracking_id"));
        ConnectedPaypalAccount account = Optional.ofNullable(merchantId).flatMap(accounts::findByPaypalMerchantId)
                .or(() -> Optional.ofNullable(trackingId).flatMap(accounts::findByTrackingId))
                .orElse(null);
        if (account == null) {
            // Not one of ours (another integration on the same app), or a stall since disconnected.
            log.info("PayPal seller event for unknown merchant {} / tracking {}; ignored", merchantId, trackingId);
            return;
        }
        if (merchantId == null) {
            merchantId = account.getPaypalMerchantId();
        }
        if (merchantId == null) {
            merchantId = partner.findMerchantId(account.getTrackingId()).orElse(null);
        }
        if (merchantId == null) {
            return;
        }
        // The event says something changed; the status read says what. Never the event's own flags,
        // for the reason CardChargeCapability gives: one rule, from one source.
        PaypalSellerStatus status = partner.sellerStatus(merchantId);
        ensureOwnership(status, account);
        saveStatus(account, status);
    }

    private void handleConsentRevoked(JsonNode resource) {
        String merchantId = text(resource.path("merchant_id"));
        if (merchantId == null) {
            throw PaypalConnectException.apiError("PayPal consent-revoked webhook has no merchant id", null);
        }
        accounts.findByPaypalMerchantId(merchantId).ifPresent(accounts::delete);
        orphanPlans(merchantId);
    }

    /**
     * The seller withdrew Imani's permissions. PayPal may keep billing its subscriptions, but Imani can neither see
     * nor cancel them any more, so every live plan on the account is marked ORPHANED for a person to follow up.
     */
    private void orphanPlans(String merchantId) {
        if (recurringPlans == null) {
            return;
        }
        recurringPlans.findByConnectedAccountIdAndStatusIn(merchantId, RecurringPlan.LIVE).stream()
                .filter(RecurringPlan::isPaypal)
                .forEach(plan -> {
                    plan.setStatus(RecurringPlan.Status.ORPHANED);
                    plan.setUpdatedAt(Instant.now());
                    recurringPlans.save(plan);
                    log.warn("[alert] paypal_plan_orphaned plan={} merchant={}", plan.getId(), merchantId);
                });
    }

    private void ensureOwnership(PaypalSellerStatus status, ConnectedPaypalAccount account) {
        if (status.trackingId() != null && !status.trackingId().equals(account.getTrackingId())) {
            throw PaypalConnectException.ownershipMismatch(status.merchantId());
        }
        if (account.getPaypalMerchantId() != null && status.merchantId() != null
                && !account.getPaypalMerchantId().equals(status.merchantId())) {
            throw PaypalConnectException.ownershipMismatch(status.merchantId());
        }
        accounts.findByPaypalMerchantId(status.merchantId())
                .filter(other -> !other.getMerchantPubkey().equals(account.getMerchantPubkey()))
                .ifPresent(other -> {
                    throw PaypalConnectException.ownershipMismatch(status.merchantId());
                });
    }

    /** @param status PayPal's answer, or null when PayPal no longer knows the seller */
    private ConnectedPaypalAccount saveStatus(ConnectedPaypalAccount account, PaypalSellerStatus status) {
        boolean canTakeCards = PaypalCardCapability.canTakeCards(status);
        boolean onboarded = status != null && status.merchantId() != null;
        List<String> due = status == null ? List.of() : status.requirementsDue();

        if (!account.isOnboardingComplete() && onboarded && due.isEmpty()) {
            metrics.connect(ConnectEvent.ONBOARDING_COMPLETED);
        }
        if (account.isChargesEnabled() && !canTakeCards) {
            metrics.connect(ConnectEvent.CHARGES_ENABLED_LOST);
        }
        if (!account.isChargesEnabled() && canTakeCards) {
            metrics.connect(ConnectEvent.CHARGES_ENABLED_GAINED);
        }

        account.setPaypalMerchantId(status == null ? null : status.merchantId());
        account.setDetailsSubmitted(onboarded);
        account.setOnboardingComplete(onboarded && due.isEmpty());
        account.setChargesEnabled(canTakeCards);
        // A PayPal sale lands in the seller's PayPal balance at once: payable as soon as it is takeable.
        account.setPayoutsEnabled(canTakeCards);
        account.setPrimaryEmailConfirmed(status != null && status.primaryEmailConfirmed());
        account.setRequirementsDue(writeRequirements(due));
        account.setDisabledReason(status == null ? null : status.disabledReason());
        account.setCountry(status == null ? null : status.country());
        account.setEmail(status == null ? null : status.primaryEmail());
        if (account.getDefaultCurrency() == null) {
            account.setDefaultCurrency(gatewayProperties.getDefaultCurrency());
        }
        account.setUpdatedAt(Instant.now());
        return accounts.save(account);
    }

    private boolean needsOnboardingLink(ConnectedPaypalAccount account) {
        return !account.isOnboardingComplete() || !account.isChargesEnabled();
    }

    private PaypalConnectAccountResponse toResponse(ConnectedPaypalAccount account, String onboardingUrl) {
        List<String> due = readRequirements(account.getRequirementsDue());
        return new PaypalConnectAccountResponse(
                account.getMerchantPubkey(),
                account.getPaypalMerchantId(),
                onboardingUrl,
                statusFor(account, due),
                account.isOnboardingComplete(),
                account.isChargesEnabled(),
                account.isPayoutsEnabled(),
                account.isDetailsSubmitted(),
                account.getDefaultCurrency(),
                due);
    }

    /** Stripe's status words, so the Payments screen needs no second vocabulary. */
    private String statusFor(ConnectedPaypalAccount account, List<String> due) {
        if (account.isPayoutsEnabled()) {
            return "payouts_enabled";
        }
        if (account.isChargesEnabled()) {
            return "charges_enabled";
        }
        if (!account.isDetailsSubmitted()) {
            return "onboarding_in_progress";
        }
        if (!due.isEmpty() || account.getDisabledReason() != null) {
            return "restricted";
        }
        return "details_submitted";
    }

    private String resolveReturnUrl(String returnUrl) {
        if (returnUrl != null && !returnUrl.isBlank()) {
            return returnUrl;
        }
        if (connectProperties.getReturnUrl() != null && !connectProperties.getReturnUrl().isBlank()) {
            return connectProperties.getReturnUrl();
        }
        throw new PaypalConnectException(PaypalConnectExceptionCode.ONBOARDING_LINK_FAILED,
                org.springframework.http.HttpStatus.BAD_REQUEST, "Missing required PayPal onboarding URL: return_url");
    }

    private List<String> readRequirements(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw PaypalConnectException.apiError("Failed to deserialize PayPal requirements", e);
        }
    }

    private String writeRequirements(List<String> due) {
        try {
            return objectMapper.writeValueAsString(due == null ? List.of() : due);
        } catch (JsonProcessingException e) {
            throw PaypalConnectException.apiError("Failed to serialize PayPal requirements", e);
        }
    }

    private ProcessedPaypalWebhookEvent newRecord(String eventId, String eventType, String hash) {
        ProcessedPaypalWebhookEvent record = new ProcessedPaypalWebhookEvent();
        record.setEventId(eventId);
        record.setEventType(eventType);
        record.setPayloadHash(hash);
        record.setLivemode(gatewayProperties.isLive());
        record.setReceivedAt(Instant.now());
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        return record;
    }

    private void markProcessed(ProcessedPaypalWebhookEvent record) {
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        record.setProcessedAt(Instant.now());
        record.setLastError(null);
        processedEvents.save(record);
    }

    /** REQUIRES_NEW, as Stripe's: the FAILED mark survives the outer rollback. */
    private void markFailed(String eventId, String eventType, String hash, String message) {
        requiresNewTx.executeWithoutResult(status -> {
            ProcessedPaypalWebhookEvent record = processedEvents.findById(eventId)
                    .orElseGet(() -> newRecord(eventId, eventType, hash));
            record.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
            record.setLastError(message == null ? null : message.substring(0, Math.min(1024, message.length())));
            processedEvents.save(record);
        });
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private void requireEnabled() {
        if (!connectProperties.isEnabled()) {
            throw PaypalConnectException.connectDisabled();
        }
    }
}
