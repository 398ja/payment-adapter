package xyz.tcheeric.payment.adapter.cash.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashEventKind;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashIntentEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.NostrEventBase;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashIntentPayload;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Webhook handler for processing cash payment intents (NIP-XX kind 5201).
 * Uses database-backed idempotency and Schnorr signature verification.
 */
@Slf4j
public class CashWebhookHandler implements WebhookHandler<CashWebhookPayload> {

    private static final String PAYMENT_TYPE = "cash";
    private static final long CLOCK_DRIFT_TOLERANCE_SECONDS = 60;
    private static final long MAX_FUTURE_SECONDS = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CashIntentRepository intentRepository;

    // Callback for notifying when an intent is received: (ref, CashIntent) -> void
    private volatile BiConsumer<String, CashIntent> intentReceivedCallback;

    /**
     * No-arg constructor required by ServiceLoader SPI.
     * Call {@link #setIntentRepository(CashIntentRepository)} before processing webhooks.
     */
    public CashWebhookHandler() {
    }

    public CashWebhookHandler(CashIntentRepository intentRepository) {
        this.intentRepository = intentRepository;
    }

    /**
     * Set the intent repository (used when handler is loaded via ServiceLoader
     * or injected by Spring when available as a bean).
     */
    @Autowired(required = false)
    public void setIntentRepository(CashIntentRepository intentRepository) {
        this.intentRepository = intentRepository;
    }

    @Override
    public String getPaymentType() {
        return PAYMENT_TYPE;
    }

    @Override
    public CashWebhookPayload parsePayload(HttpServletRequest request) throws WebhookParseException {
        try {
            // Read the request body
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String body = sb.toString();
            if (body.isEmpty()) {
                throw new WebhookParseException("Empty request body");
            }

            log.debug("Parsing cash webhook payload: {}", body.length() > 200 ? body.substring(0, 200) + "..." : body);

            // Parse the JSON
            JsonNode root = MAPPER.readTree(body);

            // Extract event fields
            String eventId = getRequiredField(root, "id");
            int kind = getRequiredIntField(root, "kind");

            // Validate event kind
            if (kind != CashEventKind.CASH_INTENT) {
                throw new WebhookParseException("Invalid event kind: expected " + CashEventKind.CASH_INTENT + ", got " + kind);
            }

            String pubkey = getRequiredField(root, "pubkey");
            Long createdAt = root.has("created_at") ? root.get("created_at").asLong() : null;
            String content = root.has("content") ? root.get("content").asText() : "";
            String sig = root.has("sig") ? root.get("sig").asText() : null;

            // Extract ref from tags
            String ref = null;
            if (root.has("tags") && root.get("tags").isArray()) {
                for (JsonNode tag : root.get("tags")) {
                    if (tag.isArray() && tag.size() >= 2 && "ref".equals(tag.get(0).asText())) {
                        ref = tag.get(1).asText();
                        break;
                    }
                }
            }

            if (ref == null) {
                throw new WebhookParseException("Missing required 'ref' tag in event");
            }

            // Check for decrypted content
            String decryptedContent = null;
            if (root.has("decrypted_content")) {
                decryptedContent = root.get("decrypted_content").asText();
            }

            // Parse intent payload from decrypted content
            String proof = null;
            Long customerTimestamp = null;
            String customerPubkey = pubkey;

            if (decryptedContent != null && !decryptedContent.isEmpty()) {
                try {
                    CashIntentPayload intentPayload = CashIntentEvent.parsePayload(decryptedContent);
                    proof = intentPayload.getProof();
                    customerTimestamp = intentPayload.getTs();
                    if (intentPayload.getFrom() != null) {
                        customerPubkey = intentPayload.getFrom();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse decrypted intent payload: {}", e.getMessage());
                }
            }

            if (customerTimestamp == null) {
                customerTimestamp = createdAt;
            }

            return CashWebhookPayload.builder()
                    .eventId(eventId)
                    .kind(kind)
                    .ref(ref)
                    .customerPubkey(customerPubkey)
                    .proof(proof)
                    .customerTimestamp(customerTimestamp)
                    .signature(sig)
                    .rawEvent(body)
                    .decryptedContent(decryptedContent)
                    .build();

        } catch (WebhookParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse cash webhook payload", e);
            throw new WebhookParseException("Failed to parse webhook payload: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateSignature(CashWebhookPayload payload, HttpServletRequest request) throws WebhookSignatureException {
        if (payload.getSignature() == null || payload.getSignature().isEmpty()) {
            throw new WebhookSignatureException("Missing event signature");
        }

        // Verify Schnorr signature using NostrEventBase
        if (payload.getRawEvent() != null) {
            boolean valid = NostrEventBase.verifySignatureFromJson(payload.getRawEvent());
            if (!valid) {
                log.warn("Invalid Schnorr signature for event: eventId={}", payload.getEventId());
                throw new WebhookSignatureException("Invalid event signature");
            }
            log.debug("Signature verified: eventId={}", payload.getEventId());
        }
    }

    @Override
    public WebhookResult handle(CashWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        String eventId = payload.getEventId();
        String ref = payload.getRef();

        log.info("Processing cash intent webhook: eventId={}, ref={}", eventId, ref);

        if (intentRepository == null) {
            throw new WebhookProcessingException("CashWebhookHandler not initialized: intentRepository is null");
        }

        // Check for duplicate via DB
        if (intentRepository.existsByEventId(eventId)) {
            log.info("Duplicate cash intent webhook: eventId={}", eventId);
            throw new WebhookDuplicateException("Event already processed: " + eventId);
        }

        try {
            // Validate ref format
            if (ref == null || ref.length() < 4 || ref.length() > 24) {
                throw new WebhookProcessingException("Invalid ref format: " + ref);
            }
            if (!ref.matches("^[0-9a-fA-F]+$")) {
                throw new WebhookProcessingException("Invalid ref: must be hex string");
            }

            // Validate timestamp
            if (payload.getCustomerTimestamp() != null) {
                long nowSeconds = Instant.now().getEpochSecond();
                long eventSeconds = payload.getCustomerTimestamp();
                if (eventSeconds > nowSeconds + MAX_FUTURE_SECONDS) {
                    throw new WebhookProcessingException("Event timestamp too far in future");
                }
            }

            // Create and persist CashIntent
            CashIntent intent = CashIntent.create(
                    ref,
                    payload.getCustomerPubkey(),
                    payload.getProof(),
                    payload.getEventTimestamp(),
                    eventId
            );
            intent.setProcessed(true);
            intentRepository.save(intent);

            log.info("Cash intent processed: eventId={}, ref={}, customerPubkey={}",
                    eventId, ref, payload.getCustomerPubkey());

            // Notify callback
            if (intentReceivedCallback != null) {
                try {
                    intentReceivedCallback.accept(ref, intent);
                } catch (Exception e) {
                    log.warn("Intent callback failed for ref={}: {}", ref, e.getMessage());
                }
            }

            return WebhookResult.success(ref, State.PENDING);

        } catch (WebhookProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to process cash intent webhook: eventId={}", eventId, e);
            throw new WebhookProcessingException("Failed to process cash intent: " + e.getMessage(), e);
        }
    }

    /**
     * Set a callback to be notified when an intent is received.
     */
    public void setIntentReceivedCallback(BiConsumer<String, CashIntent> callback) {
        this.intentReceivedCallback = callback;
    }

    private String getRequiredField(JsonNode node, String field) throws WebhookParseException {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new WebhookParseException("Missing required field: " + field);
        }
        return node.get(field).asText();
    }

    private int getRequiredIntField(JsonNode node, String field) throws WebhookParseException {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new WebhookParseException("Missing required field: " + field);
        }
        return node.get(field).asInt();
    }
}
