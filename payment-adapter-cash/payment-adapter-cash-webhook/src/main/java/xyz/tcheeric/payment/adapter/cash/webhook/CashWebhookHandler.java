package xyz.tcheeric.payment.adapter.cash.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashEventKind;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashIntentEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashIntentPayload;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.io.BufferedReader;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook handler for processing cash payment intents (NIP-XX kind 5201).
 * This handler receives events from Nostr relays when customers signal
 * their intent to pay cash.
 *
 * <p>The webhook endpoint expects a JSON body containing:
 * <ul>
 *   <li>The Nostr event (kind 5201)</li>
 *   <li>Optionally, the decrypted content if the relay/proxy decrypted it</li>
 * </ul>
 */
@Slf4j
public class CashWebhookHandler implements WebhookHandler<CashWebhookPayload> {

    private static final String PAYMENT_TYPE = "cash";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // In-memory store for processed intents (use database in production)
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
    private final Map<String, CashIntent> intents = new ConcurrentHashMap<>();

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

            // Check for decrypted content (might be provided separately by relay/proxy)
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

            // Use event timestamp if customer timestamp not available
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
        // Validate Nostr event signature per NIP-01
        if (payload.getSignature() == null || payload.getSignature().isEmpty()) {
            throw new WebhookSignatureException("Missing event signature");
        }

        // TODO: Implement NIP-01 signature verification using nostr-java
        // For now, we trust the relay to have validated the signature
        log.debug("Signature validation skipped (relay trusted): eventId={}", payload.getEventId());
    }

    @Override
    public WebhookResult handle(CashWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        String eventId = payload.getEventId();
        String ref = payload.getRef();

        log.info("Processing cash intent webhook: eventId={}, ref={}", eventId, ref);

        // Check for duplicate
        if (processedEventIds.contains(eventId)) {
            log.info("Duplicate cash intent webhook: eventId={}", eventId);
            throw new WebhookDuplicateException("Event already processed: " + eventId);
        }

        try {
            // Validate ref (would check against stored invoices in production)
            if (ref == null || ref.length() < 4) {
                throw new WebhookProcessingException("Invalid ref: " + ref);
            }

            // Validate timestamp (reject events too far in the future)
            if (payload.getCustomerTimestamp() != null) {
                long nowSeconds = Instant.now().getEpochSecond();
                long eventSeconds = payload.getCustomerTimestamp();
                if (eventSeconds > nowSeconds + 300) { // 5 min tolerance
                    throw new WebhookProcessingException("Event timestamp too far in future");
                }
            }

            // Create CashIntent record
            CashIntent intent = CashIntent.create(
                    ref,
                    payload.getCustomerPubkey(),
                    payload.getProof(),
                    payload.getEventTimestamp(),
                    eventId
            );
            intent.setProcessed(true);

            // Store the intent
            intents.put(eventId, intent);
            processedEventIds.add(eventId);

            log.info("Cash intent processed: eventId={}, ref={}, customerPubkey={}",
                    eventId, ref, payload.getCustomerPubkey());

            // TODO: Notify the CashGateway/merchant that an intent was received
            // This could trigger a WebSocket notification to the merchant UI

            return WebhookResult.success(ref, State.PENDING);

        } catch (WebhookProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to process cash intent webhook: eventId={}", eventId, e);
            throw new WebhookProcessingException("Failed to process cash intent: " + e.getMessage(), e);
        }
    }

    /**
     * Get a processed intent by event ID.
     */
    public CashIntent getIntent(String eventId) {
        return intents.get(eventId);
    }

    /**
     * Get all intents for a specific invoice ref.
     */
    public Set<CashIntent> getIntentsByRef(String ref) {
        Set<CashIntent> result = new HashSet<>();
        for (CashIntent intent : intents.values()) {
            if (ref.equals(intent.getRef())) {
                result.add(intent);
            }
        }
        return result;
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
