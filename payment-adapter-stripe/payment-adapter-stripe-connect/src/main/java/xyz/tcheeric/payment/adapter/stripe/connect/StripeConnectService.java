package xyz.tcheeric.payment.adapter.stripe.connect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.ConnectEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringEligibility;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeConnectionType;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeOAuthState;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeOAuthStateRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectExceptionCode;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectOAuthStartResponse;
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
    private final StripeOAuthStateRepository oauthStateRepository;
    private final RecurringPlanRepository recurringPlans;
    private final TransactionTemplate requiresNewTx;

    /** imani-deploy#210. Connect lifecycle transitions, seen here first via account.updated. */
    private PaymentMetrics metrics = new PaymentMetrics();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry).preRegisterConnect();
    }

    public StripeConnectService(
            StripeConnectProperties connectProperties,
            StripeGatewayProperties gatewayProperties,
            StripeConnectClient stripeConnectClient,
            ObjectMapper objectMapper,
            ConnectedStripeAccountRepository connectedStripeAccountRepository,
            ProcessedStripeWebhookEventRepository processedEventRepository,
            StripeOAuthStateRepository oauthStateRepository,
            RecurringPlanRepository recurringPlans,
            PlatformTransactionManager transactionManager) {
        this.connectProperties = connectProperties;
        this.gatewayProperties = gatewayProperties;
        this.stripeConnectClient = stripeConnectClient;
        this.objectMapper = objectMapper;
        this.connectedStripeAccountRepository = connectedStripeAccountRepository;
        this.processedEventRepository = processedEventRepository;
        this.oauthStateRepository = oauthStateRepository;
        this.recurringPlans = recurringPlans;
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
        // Stripe keeps charging buyers after a disconnect and Imani stops seeing it (spec R8).
        java.util.Optional<ConnectedStripeAccount> found = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey);
        found.ifPresent(account -> {
            if (recurringPlans.existsByConnectedAccountIdAndStatusIn(account.getStripeAccountId(), RecurringPlan.CHARGING)) {
                throw StripeConnectException.activePlans();
            }
        });
        connectedStripeAccountRepository.deleteByMerchantPubkey(merchantPubkey);
        // An account the stall connected through OAuth is its own: Stop must also remove Imani from its connected
        // apps, or Imani's platform key could still act on it. After the plan guard, so a refused Stop changes
        // nothing. Best effort: the row is gone either way, and a failure is logged for an operator.
        found.filter(account -> account.getConnectionType() == StripeConnectionType.OAUTH)
                .filter(account -> connectProperties.getOauth().isConfigured())
                .ifPresent(account -> afterCommit(() -> deauthorizeQuietly(account.getStripeAccountId())));
    }

    /** Runs once the row's deletion is committed, so a rollback never leaves Imani removed from a kept account. */
    private static void afterCommit(Runnable action) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
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
            // Exhaustive over StripeConnectEvent (#286): what is handled is what the subscription
            // check asks Stripe for.
            Optional<StripeConnectEvent> handled = StripeConnectEvent.fromType(event.getType());
            if (handled.isEmpty()) {
                markProcessed(eventRecord);
                return new StripeConnectWebhookResponse(event.getId(), "ignored");
            }
            Runnable handler = switch (handled.get()) {
                case ACCOUNT_UPDATED -> () -> handleAccountUpdated(event);
                case ACCOUNT_APPLICATION_DEAUTHORIZED -> () -> handleAccountDeauthorized(event);
            };
            handler.run();
            markProcessed(eventRecord);
            return new StripeConnectWebhookResponse(event.getId(), "processed");
        } catch (RuntimeException e) {
            markFailed(event, currentHash, e.getMessage());
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
        // The stall disconnected on Stripe's side. Its subscriptions keep running there, but Imani can no longer see or
        // end them, so every live plan is marked ORPHANED rather than left looking healthy (spec R8).
        recurringPlans.findByConnectedAccountIdAndStatusIn(stripeAccountId, RecurringPlan.LIVE).forEach(plan -> {
            plan.setStatus(RecurringPlan.Status.ORPHANED);
            plan.setUpdatedAt(Instant.now());
            recurringPlans.save(plan);
            log.warn("[alert] recurring_plan_orphaned plan={} account={}", plan.getId(), stripeAccountId);
        });
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
                // The same rule the API path uses, and it MUST be the same one.
                // This snapshot is built from the `account.updated` webhook and
                // goes straight into the database via saveSnapshot, so a raw
                // `charges_enabled` here would overwrite a correct false with
                // the old lie — on the event most likely to arrive right after
                // onboarding. See CardChargeCapability for what the lie was.
                CardChargeCapability.canTakeCards(account),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                detailsSubmitted,
                account.getDefaultCurrency(),
                requirementsDue,
                account.getRequirements() == null ? null : account.getRequirements().getDisabledReason(),
                account.getCountry(),
                account.getEmail(),
                AccountControls.type(account),
                AccountControls.feePayer(account),
                AccountControls.lossesPayer(account),
                AccountControls.dashboardType(account)
        );
    }

    private ConnectedStripeAccount saveSnapshot(String merchantPubkey, StripeAccountSnapshot snapshot) {
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .orElseGet(ConnectedStripeAccount::new);
        recordTransitions(account, snapshot);
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
        // Recorded when Stripe reports them and never blanked afterwards: the fee payer and dashboard type are
        // fixed at creation, and an event that carries no `controller` block must not erase what we learned.
        if (snapshot.accountType() != null) {
            account.setAccountType(snapshot.accountType());
        }
        if (snapshot.feePayer() != null) {
            account.setFeePayer(snapshot.feePayer());
        }
        if (snapshot.lossesPayer() != null) {
            account.setLossesPayer(snapshot.lossesPayer());
        }
        if (snapshot.dashboardType() != null) {
            account.setDashboardType(snapshot.dashboardType());
        }
        account.setUpdatedAt(Instant.now());
        if (account.getCreatedAt() == null) {
            account.setCreatedAt(Instant.now());
        }
        return connectedStripeAccountRepository.save(account);
    }

    /** Count what changed, comparing the stored row (a new one has an id-less default) with Stripe's. */
    private void recordTransitions(ConnectedStripeAccount before, StripeAccountSnapshot after) {
        boolean isNew = before.getStripeAccountId() == null;
        if (isNew) {
            metrics.connect(ConnectEvent.ONBOARDING_STARTED);
        }
        if (!before.isOnboardingComplete() && after.onboardingComplete()) {
            metrics.connect(ConnectEvent.ONBOARDING_COMPLETED);
        }
        if (!isNew && before.isChargesEnabled() && !after.chargesEnabled()) {
            metrics.connect(ConnectEvent.CHARGES_ENABLED_LOST);
        }
        if (!before.isChargesEnabled() && after.chargesEnabled()) {
            metrics.connect(ConnectEvent.CHARGES_ENABLED_GAINED);
        }
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
                requirementsDue,
                account.getConnectionType() == null ? null : account.getConnectionType().name(),
                account.getFeePayer(),
                account.getLossesPayer(),
                RecurringEligibility.eligible(account),
                account.getStripeAccountId() != null && recurringPlans.existsByConnectedAccountIdAndStatusIn(
                        account.getStripeAccountId(), RecurringPlan.CHARGING)
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

    private void markFailed(Event event, String payloadHash, String message) {
        // Runs in REQUIRES_NEW so the FAILED audit record commits independently
        // of the outer @Transactional rollback. For a FIRST-TIME failure the
        // outer transaction's PROCESSING insert is not yet committed and is
        // invisible here, so findById returns empty — create the record instead
        // of silently dropping the failure (no audit/retry state otherwise).
        requiresNewTx.executeWithoutResult(status -> {
            ProcessedStripeWebhookEvent record = processedEventRepository.findById(event.getId())
                    .orElseGet(() -> {
                        ProcessedStripeWebhookEvent created = new ProcessedStripeWebhookEvent();
                        created.setEventId(event.getId());
                        created.setEventType(event.getType());
                        created.setPayloadHash(payloadHash);
                        created.setLivemode(Boolean.TRUE.equals(event.getLivemode()));
                        created.setReceivedAt(Instant.now());
                        return created;
                    });
            record.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
            record.setLastError(message);
            processedEventRepository.save(record);
        });
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

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Rows that expired more than this long ago are dropped when a new flow starts. */
    private static final java.time.Duration STATE_RETENTION = java.time.Duration.ofHours(1);

    /**
     * Step 1 of connecting a stall's own Stripe account. Returns the Stripe page to send the farm's browser to.
     *
     * <p>The {@code state} in that URL is a random single-use token stored against {@code merchantPubkey}. It is
     * the only link between the browser that comes back and the merchant who asked, so the merchant is never
     * taken from the callback: {@link #completeOAuth} reads it back from here.
     *
     * <p>A merchant that already has an account is refused now, before the farm is sent to Stripe and consents
     * to something we would then have to reject.
     */
    public StripeConnectOAuthStartResponse startOAuth(String merchantPubkey) {
        requireEnabled();
        requireOAuth();
        if (merchantPubkey == null || merchantPubkey.isBlank()) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.OAUTH_STATE_INVALID, "merchant_pubkey is required");
        }
        if (connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey).isPresent()) {
            throw StripeConnectException.merchantAlreadyLinked(
                    "This merchant already has a connected Stripe account. Disconnect it first.");
        }
        Instant now = Instant.now();
        StripeOAuthState state = new StripeOAuthState();
        state.setState(newState());
        state.setMerchantPubkey(merchantPubkey);
        state.setCreatedAt(now);
        state.setExpiresAt(now.plus(connectProperties.getOauth().getStateTtl()));
        requiresNewTx.executeWithoutResult(tx -> {
            oauthStateRepository.deleteExpiredBefore(now.minus(STATE_RETENTION));
            oauthStateRepository.save(state);
        });
        String url = stripeConnectClient.oauthAuthorizeUrl(
                connectProperties.getOauth().getClientId(),
                connectProperties.getOauth().getRedirectUri(),
                state.getState());
        return new StripeConnectOAuthStartResponse(url, state.getExpiresAt());
    }

    /**
     * Step 2: the farm consented and Stripe sent the browser back with a {@code code} and our {@code state}.
     *
     * <p>Order matters, and each step exists because of how this can go wrong:
     * <ol>
     * <li><b>Consume the state first, in its own committed transaction.</b> The code can be used once; Stripe
     * revokes the connection on a second use. If the state were consumed in the same transaction as the later
     * database work and that work failed, the rollback would hand the same state (and so the same code) a second
     * chance. Committed first, a failure anywhere below leaves the state spent and the farm starts again.</li>
     * <li><b>Exchange the code exactly once</b> ({@code exchangeOAuthCode} never retries).</li>
     * <li><b>Read the account back from Stripe</b> rather than trusting the response, and refuse one that carries
     * another merchant's {@code merchant_pubkey}.</li>
     * <li><b>Refuse a double claim:</b> one Stripe account maps to one merchant, one merchant to one account.</li>
     * </ol>
     *
     * <p>The account is stored even if it cannot take cards yet. It is the farm's own, and its status tells the
     * wallet what is left to do; refusing would force the farm through consent again.
     */
    public StripeConnectAccountResponse completeOAuth(String code, String state) {
        requireEnabled();
        requireOAuth();
        if (code == null || code.isBlank() || code.length() > 256
                || state == null || state.isBlank() || state.length() > 64) {
            throw StripeConnectException.oauthStateInvalid();
        }
        Instant now = Instant.now();
        String merchantPubkey = requiresNewTx.execute(tx -> oauthStateRepository.consume(state, now) == 1
                ? oauthStateRepository.findById(state).map(StripeOAuthState::getMerchantPubkey).orElse(null)
                : null);
        if (merchantPubkey == null) {
            throw StripeConnectException.oauthStateInvalid();
        }

        String stripeAccountId = stripeConnectClient.exchangeOAuthCode(code);
        StripeAccountSnapshot snapshot = stripeConnectClient.retrieveAccount(stripeAccountId);
        ensureOwnership(snapshot, merchantPubkey);

        try {
            return requiresNewTx.execute(tx -> {
                connectedStripeAccountRepository.findByStripeAccountId(stripeAccountId)
                        .filter(existing -> !existing.getMerchantPubkey().equals(merchantPubkey))
                        .ifPresent(existing -> {
                            throw StripeConnectException.merchantAlreadyLinked(
                                    "This Stripe account is already connected to another merchant.");
                        });
                connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                        .filter(existing -> !existing.getStripeAccountId().equals(stripeAccountId))
                        .ifPresent(existing -> {
                            throw StripeConnectException.merchantAlreadyLinked(
                                    "This merchant already has a connected Stripe account. Disconnect it first.");
                        });
                ConnectedStripeAccount saved = saveSnapshot(merchantPubkey, snapshot);
                if (saved.getConnectionType() != StripeConnectionType.OAUTH) {
                    saved.setConnectionType(StripeConnectionType.OAUTH);
                    saved = connectedStripeAccountRepository.save(saved);
                }
                return toResponse(saved, null);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two callbacks for the same Stripe account (or merchant) that both passed the checks above: the
            // unique constraints decide the winner, and the loser gets the same answer as a late claim, not a 500.
            throw StripeConnectException.merchantAlreadyLinked(
                    "This Stripe account or merchant was connected by another request. Check the status.");
        }
    }

    private void deauthorizeQuietly(String stripeAccountId) {
        try {
            stripeConnectClient.deauthorize(connectProperties.getOauth().getClientId(), stripeAccountId);
        } catch (RuntimeException e) {
            log.warn("[alert] stripe_oauth_deauthorize_failed account={} error={}", stripeAccountId, e.getMessage());
        }
    }

    private static String newState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireOAuth() {
        if (!connectProperties.getOauth().isConfigured()) {
            throw StripeConnectException.oauthDisabled();
        }
    }

    private void requireEnabled() {
        if (!connectProperties.isEnabled()) {
            throw StripeConnectException.connectDisabled();
        }
    }
}
