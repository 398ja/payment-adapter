package xyz.tcheeric.payment.adapter.stripe.connect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectExceptionCode;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectWebhookResponse;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

@Slf4j
@Service
public class StripeConnectService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final StripeConnectProperties connectProperties;
    private final StripeGatewayProperties gatewayProperties;
    private final StripeConnectClient stripeConnectClient;
    private final ObjectMapper objectMapper;
    private final ConnectedStripeAccountRepository connectedStripeAccountRepository;
    private final ProcessedStripeWebhookEventRepository processedEventRepository;
    private final TransactionTemplate requiresNewTx;

    public StripeConnectService(
            StripeConnectProperties connectProperties,
            StripeGatewayProperties gatewayProperties,
            StripeConnectClient stripeConnectClient,
            ObjectMapper objectMapper,
            ConnectedStripeAccountRepository connectedStripeAccountRepository,
            ProcessedStripeWebhookEventRepository processedEventRepository,
            PlatformTransactionManager transactionManager) {
        this.connectProperties = connectProperties;
        this.gatewayProperties = gatewayProperties;
        this.stripeConnectClient = stripeConnectClient;
        this.objectMapper = objectMapper;
        this.connectedStripeAccountRepository = connectedStripeAccountRepository;
        this.processedEventRepository = processedEventRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public StripeConnectAccountResponse createOrResume(String merchantPubkey, String returnUrl, String refreshUrl) {
        requireEnabled();
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .map(existing -> refreshExistingAccount(existing, merchantPubkey))
                .orElseGet(() -> createNewAccount(merchantPubkey));

        String onboardingUrl = needsOnboardingLink(account)
                ? stripeConnectClient.createOnboardingLink(
                        account.getStripeAccountId(),
                        resolveReturnUrl(returnUrl),
                        resolveRefreshUrl(refreshUrl))
                : null;

        return toResponse(account, onboardingUrl);
    }

    @Transactional
    public StripeConnectAccountResponse refresh(String merchantPubkey, String returnUrl, String refreshUrl) {
        requireEnabled();
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .map(existing -> refreshExistingAccount(existing, merchantPubkey))
                .orElseThrow(() -> new StripeConnectException(
                        StripeConnectExceptionCode.ACCOUNT_NOT_FOUND,
                        "No Stripe connected account exists for merchant"));

        String onboardingUrl = stripeConnectClient.createOnboardingLink(
                account.getStripeAccountId(),
                resolveReturnUrl(returnUrl),
                resolveRefreshUrl(refreshUrl));
        return toResponse(account, onboardingUrl);
    }

    @Transactional
    public StripeConnectAccountResponse getStatus(String merchantPubkey) {
        requireEnabled();
        return connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .map(existing -> toResponse(refreshExistingAccount(existing, merchantPubkey), null))
                .orElseGet(() -> new StripeConnectAccountResponse(
                        merchantPubkey, null, null, "not_connected", false, false, false, false, null, List.of()));
    }

    @Transactional
    public void disconnect(String merchantPubkey) {
        requireEnabled();
        connectedStripeAccountRepository.deleteByMerchantPubkey(merchantPubkey);
    }

    @Transactional
    public StripeConnectWebhookResponse handleWebhook(String payload, String signatureHeader) {
        requireEnabled();
        Event event = stripeConnectClient.constructWebhookEvent(payload, signatureHeader, connectProperties.getWebhookSecret());

        String currentHash = sha256(payload);
        Optional<ProcessedStripeWebhookEvent> existing = processedEventRepository.findById(event.getId());
        if (existing.isPresent()) {
            ProcessedStripeWebhookEvent prev = existing.get();
            if (prev.getProcessingStatus() == StripeWebhookProcessingStatus.PROCESSED) {
                if (!currentHash.equals(prev.getPayloadHash())) {
                    log.warn("Webhook {} redelivered with different payload hash (stored={}, received={})",
                            event.getId(), prev.getPayloadHash(), currentHash);
                }
                return new StripeConnectWebhookResponse(event.getId(), "duplicate");
            }
        }

        ProcessedStripeWebhookEvent eventRecord = existing.orElseGet(() -> startEventRecord(event, payload, currentHash));
        eventRecord.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        eventRecord.setLastError(null);
        processedEventRepository.save(eventRecord);
        try {
            switch (event.getType()) {
                case "account.updated" -> handleAccountUpdated(event);
                case "account.application.deauthorized" -> handleAccountDeauthorized(event);
                default -> {
                    markProcessed(eventRecord);
                    return new StripeConnectWebhookResponse(event.getId(), "ignored");
                }
            }
            markProcessed(eventRecord);
            return new StripeConnectWebhookResponse(event.getId(), "processed");
        } catch (RuntimeException e) {
            markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    private ConnectedStripeAccount createNewAccount(String merchantPubkey) {
        StripeAccountSnapshot snapshot = stripeConnectClient.createConnectedAccount(
                merchantPubkey,
                connectProperties.getCountry());
        ensureOwnership(snapshot, merchantPubkey);
        return saveSnapshot(merchantPubkey, snapshot);
    }

    private ConnectedStripeAccount refreshExistingAccount(ConnectedStripeAccount existing, String merchantPubkey) {
        try {
            StripeAccountSnapshot snapshot = stripeConnectClient.retrieveAccount(existing.getStripeAccountId());
            ensureOwnership(snapshot, merchantPubkey);
            return saveSnapshot(merchantPubkey, snapshot);
        } catch (StripeConnectException e) {
            if (e.getCode() == StripeConnectExceptionCode.ACCOUNT_NOT_FOUND) {
                connectedStripeAccountRepository.delete(existing);
                return createNewAccount(merchantPubkey);
            }
            throw e;
        }
    }

    private boolean needsOnboardingLink(ConnectedStripeAccount account) {
        return account.getStripeAccountId() != null
                && (!account.isOnboardingComplete() || !account.isPayoutsEnabled());
    }

    private void handleAccountUpdated(Event event) {
        Account account = extractAccount(event);
        StripeAccountSnapshot snapshot = toSnapshot(account);
        ConnectedStripeAccount existing = connectedStripeAccountRepository.findByStripeAccountId(snapshot.stripeAccountId())
                .orElse(null);

        String merchantPubkey = existing != null ? existing.getMerchantPubkey() : snapshot.merchantPubkey();
        if (merchantPubkey == null || merchantPubkey.isBlank()) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.OWNERSHIP_MISMATCH,
                    "Webhook account payload is missing merchant ownership");
        }

        ensureOwnership(snapshot, merchantPubkey);
        saveSnapshot(merchantPubkey, snapshot);
    }

    private void handleAccountDeauthorized(Event event) {
        String stripeAccountId = event.getAccount();
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.STRIPE_API_ERROR,
                    "Deauthorization webhook missing account ID");
        }
        connectedStripeAccountRepository.findByStripeAccountId(stripeAccountId)
                .ifPresent(connectedStripeAccountRepository::delete);
    }

    private Account extractAccount(Event event) {
        StripeObject object = event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new StripeConnectException(
                        StripeConnectExceptionCode.STRIPE_API_ERROR,
                        "Webhook did not contain an account object"));
        if (!(object instanceof Account account)) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.STRIPE_API_ERROR,
                    "Webhook did not contain a Stripe account");
        }
        return account;
    }

    private StripeAccountSnapshot toSnapshot(Account account) {
        List<String> requirementsDue = account.getRequirements() == null || account.getRequirements().getCurrentlyDue() == null
                ? List.of()
                : List.copyOf(account.getRequirements().getCurrentlyDue());
        boolean detailsSubmitted = Boolean.TRUE.equals(account.getDetailsSubmitted());
        return new StripeAccountSnapshot(
                account.getMetadata() == null ? null : account.getMetadata().get("merchant_pubkey"),
                account.getId(),
                detailsSubmitted && requirementsDue.isEmpty(),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                detailsSubmitted,
                account.getDefaultCurrency(),
                requirementsDue,
                account.getRequirements() == null ? null : account.getRequirements().getDisabledReason(),
                account.getCountry(),
                account.getEmail()
        );
    }

    private ConnectedStripeAccount saveSnapshot(String merchantPubkey, StripeAccountSnapshot snapshot) {
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .orElseGet(ConnectedStripeAccount::new);
        account.setMerchantPubkey(merchantPubkey);
        account.setStripeAccountId(snapshot.stripeAccountId());
        account.setOnboardingComplete(snapshot.onboardingComplete());
        account.setChargesEnabled(snapshot.chargesEnabled());
        account.setPayoutsEnabled(snapshot.payoutsEnabled());
        account.setDetailsSubmitted(snapshot.detailsSubmitted());
        account.setDefaultCurrency(snapshot.defaultCurrency() != null
                ? snapshot.defaultCurrency()
                : gatewayProperties.getDefaultCurrency());
        account.setRequirementsDue(writeRequirements(snapshot.requirementsDue()));
        account.setDisabledReason(snapshot.disabledReason());
        account.setCountry(snapshot.country());
        account.setEmail(snapshot.email());
        account.setUpdatedAt(Instant.now());
        if (account.getCreatedAt() == null) {
            account.setCreatedAt(Instant.now());
        }
        return connectedStripeAccountRepository.save(account);
    }

    private StripeConnectAccountResponse toResponse(ConnectedStripeAccount account, String onboardingUrl) {
        List<String> requirementsDue = readRequirements(account.getRequirementsDue());
        return new StripeConnectAccountResponse(
                account.getMerchantPubkey(),
                account.getStripeAccountId(),
                onboardingUrl,
                statusFor(account, requirementsDue),
                account.isOnboardingComplete(),
                account.isChargesEnabled(),
                account.isPayoutsEnabled(),
                account.isDetailsSubmitted(),
                account.getDefaultCurrency(),
                requirementsDue
        );
    }

    private String statusFor(ConnectedStripeAccount account, List<String> requirementsDue) {
        if (account.getStripeAccountId() == null) {
            return "not_connected";
        }
        if (account.isPayoutsEnabled()) {
            return "payouts_enabled";
        }
        if (account.isChargesEnabled()) {
            return "charges_enabled";
        }
        if (!account.isDetailsSubmitted()) {
            return "onboarding_in_progress";
        }
        if (!requirementsDue.isEmpty()) {
            return "restricted";
        }
        return "details_submitted";
    }

    private void ensureOwnership(StripeAccountSnapshot snapshot, String merchantPubkey) {
        if (snapshot.merchantPubkey() != null && !snapshot.merchantPubkey().equals(merchantPubkey)) {
            throw StripeConnectException.ownershipMismatch(snapshot.stripeAccountId());
        }
    }

    private List<String> readRequirements(String requirementsJson) {
        if (requirementsJson == null || requirementsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(requirementsJson, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.STRIPE_API_ERROR,
                    "Failed to deserialize Stripe requirements",
                    e);
        }
    }

    private String writeRequirements(List<String> requirementsDue) {
        try {
            return objectMapper.writeValueAsString(requirementsDue == null ? List.of() : requirementsDue);
        } catch (JsonProcessingException e) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.STRIPE_API_ERROR,
                    "Failed to serialize Stripe requirements",
                    e);
        }
    }

    private String resolveReturnUrl(String returnUrl) {
        return resolveUrl(returnUrl, connectProperties.getReturnUrl(), "return_url");
    }

    private String resolveRefreshUrl(String refreshUrl) {
        return resolveUrl(refreshUrl, connectProperties.getRefreshUrl(), "refresh_url");
    }

    private String resolveUrl(String requestUrl, String defaultUrl, String fieldName) {
        if (requestUrl != null && !requestUrl.isBlank()) {
            return requestUrl;
        }
        if (defaultUrl != null && !defaultUrl.isBlank()) {
            return defaultUrl;
        }
        throw new StripeConnectException(
                StripeConnectExceptionCode.ONBOARDING_LINK_FAILED,
                "Missing required Stripe Connect URL: " + fieldName);
    }

    private ProcessedStripeWebhookEvent startEventRecord(Event event, String payload, String payloadHash) {
        ProcessedStripeWebhookEvent record = new ProcessedStripeWebhookEvent();
        record.setEventId(event.getId());
        record.setEventType(event.getType());
        record.setPayloadHash(payloadHash);
        record.setLivemode(Boolean.TRUE.equals(event.getLivemode()));
        record.setReceivedAt(Instant.now());
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        return processedEventRepository.save(record);
    }

    private void markProcessed(ProcessedStripeWebhookEvent record) {
        record.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        record.setProcessedAt(Instant.now());
        record.setLastError(null);
        processedEventRepository.save(record);
    }

    private void markFailed(String eventId, String message) {
        requiresNewTx.executeWithoutResult(status ->
                processedEventRepository.findById(eventId).ifPresent(record -> {
                    record.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
                    record.setLastError(message);
                    processedEventRepository.save(record);
                }));
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.STRIPE_API_ERROR,
                    "Failed to hash webhook payload",
                    e);
        }
    }

    private void requireEnabled() {
        if (!connectProperties.isEnabled()) {
            throw StripeConnectException.connectDisabled();
        }
    }
}
