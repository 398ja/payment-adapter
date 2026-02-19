package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashIntentPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * CashIntent event (kind 5201) for NIP-XX Cash Payments.
 * Customer sends this to signal intent to pay cash.
 */
@Slf4j
@Getter
public class CashIntentEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;
    private final CashIntentPayload payload;

    private CashIntentEvent(GenericEvent event, CashIntentPayload payload) {
        this.event = event;
        this.payload = payload;
    }

    /**
     * Builder for creating CashIntent events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PublicKey customerPubkey;
        private PublicKey merchantPubkey;
        private String ref;
        private String proof;
        private String encryptedContent;

        public Builder customerPubkey(PublicKey pubkey) {
            this.customerPubkey = pubkey;
            return this;
        }

        public Builder merchantPubkey(PublicKey pubkey) {
            this.merchantPubkey = pubkey;
            return this;
        }

        public Builder ref(String ref) {
            this.ref = ref;
            return this;
        }

        public Builder proof(String proof) {
            this.proof = proof;
            return this;
        }

        public Builder encryptedContent(String encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public CashIntentEvent build() {
            if (customerPubkey == null) {
                throw new IllegalStateException("customerPubkey is required");
            }
            if (merchantPubkey == null) {
                throw new IllegalStateException("merchantPubkey is required");
            }
            if (ref == null) {
                throw new IllegalStateException("ref is required");
            }

            List<BaseTag> tags = new ArrayList<>();

            // Recipient pubkey tag (merchant's ephemeral key)
            tags.add(BaseTag.create("p", merchantPubkey.toString()));

            // Ref tag
            tags.add(BaseTag.create("ref", ref));

            // Build the payload
            long timestamp = System.currentTimeMillis() / 1000;
            CashIntentPayload payload = CashIntentPayload.builder()
                    .ref(ref)
                    .from(customerPubkey.toString())
                    .proof(proof)
                    .ts(timestamp)
                    .build();

            // Create the event using Integer kind constructor
            GenericEvent event = new GenericEvent(customerPubkey, CashEventKind.CASH_INTENT, tags, "");
            event.setCreatedAt(timestamp);

            // Set encrypted content
            if (encryptedContent != null) {
                event.setContent(encryptedContent);
            } else {
                event.setContent("");
            }

            return new CashIntentEvent(event, payload);
        }
    }

    /**
     * Serialize the payload to JSON for encryption.
     */
    public String serializePayload() throws JsonProcessingException {
        return MAPPER.writeValueAsString(payload);
    }

    /**
     * Get the invoice reference from the event.
     */
    public String getRef() {
        return payload.getRef();
    }

    /**
     * Get the customer's public key.
     */
    public String getCustomerPubkey() {
        return payload.getFrom();
    }

    /**
     * Get the proof code (if provided).
     */
    public String getProof() {
        return payload.getProof();
    }

    /**
     * Parse a CashIntentEvent from a GenericEvent and decrypted payload.
     */
    public static CashIntentEvent fromGenericEvent(GenericEvent event, CashIntentPayload payload) {
        if (event.getKind() != CashEventKind.CASH_INTENT) {
            throw new IllegalArgumentException("Event kind must be " + CashEventKind.CASH_INTENT);
        }
        return new CashIntentEvent(event, payload);
    }

    /**
     * Parse the payload from decrypted JSON content.
     */
    public static CashIntentPayload parsePayload(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, CashIntentPayload.class);
    }
}
