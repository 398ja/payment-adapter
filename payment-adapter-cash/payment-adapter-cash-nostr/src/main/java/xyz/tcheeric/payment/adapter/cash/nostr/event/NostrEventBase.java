package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.Signature;
import nostr.crypto.schnorr.Schnorr;
import nostr.event.impl.GenericEvent;

/**
 * Base class providing shared functionality for all NIP-XX cash event types.
 * Wraps a GenericEvent and provides signature verification using Schnorr signatures.
 */
@Slf4j
@Getter
public class NostrEventBase {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;

    protected NostrEventBase(GenericEvent event) {
        this.event = event;
    }

    /**
     * Verify the Schnorr signature on this event per NIP-01.
     *
     * @return true if signature is valid
     */
    public boolean verifySignature() {
        try {
            String id = event.getId();
            Signature sig = event.getSignature();
            String pubkeyHex = event.getPubKey() != null ? event.getPubKey().toString() : null;

            if (id == null || sig == null || pubkeyHex == null) {
                log.warn("Cannot verify signature: missing id, sig, or pubkey");
                return false;
            }

            byte[] idBytes = hexToBytes(id);
            byte[] sigBytes = sig.getRawData();
            byte[] pubkeyBytes = event.getPubKey().getRawData();

            return Schnorr.verify(idBytes, pubkeyBytes, sigBytes);
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify a Schnorr signature from raw event JSON.
     * Useful for webhook signature validation without a full GenericEvent.
     *
     * @param rawEventJson raw Nostr event JSON string
     * @return true if signature is valid
     */
    public static boolean verifySignatureFromJson(String rawEventJson) {
        try {
            JsonNode root = MAPPER.readTree(rawEventJson);

            String id = root.has("id") ? root.get("id").asText() : null;
            String sig = root.has("sig") ? root.get("sig").asText() : null;
            String pubkey = root.has("pubkey") ? root.get("pubkey").asText() : null;

            if (id == null || sig == null || pubkey == null) {
                return false;
            }

            byte[] idBytes = hexToBytes(id);
            byte[] sigBytes = hexToBytes(sig);
            byte[] pubkeyBytes = hexToBytes(pubkey);

            return Schnorr.verify(idBytes, pubkeyBytes, sigBytes);
        } catch (Exception e) {
            log.error("Signature verification from JSON failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Serialize the event to JSON string.
     */
    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(event);
    }

    /**
     * Get the event kind.
     */
    public int getKind() {
        return event.getKind();
    }

    /**
     * Get the event ID.
     */
    public String getId() {
        return event.getId();
    }

    /**
     * Get the event timestamp.
     */
    public long getCreatedAt() {
        return event.getCreatedAt();
    }

    /**
     * Convert hex string to byte array.
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
