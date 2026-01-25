package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashCancelPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * CashCancel event (kind 5203) for NIP-XX Cash Payments.
 * Used for cancellation, timeout, or decline by either party.
 */
@Slf4j
@Getter
public class CashCancelEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;
    private final CashCancelPayload payload;

    private CashCancelEvent(GenericEvent event, CashCancelPayload payload) {
        this.event = event;
        this.payload = payload;
    }

    /**
     * Builder for creating CashCancel events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PublicKey senderPubkey;
        private PublicKey recipientPubkey;
        private String ref;
        private String status = "cancelled";
        private String reason;
        private String encryptedContent;

        public Builder senderPubkey(PublicKey pubkey) {
            this.senderPubkey = pubkey;
            return this;
        }

        public Builder recipientPubkey(PublicKey pubkey) {
            this.recipientPubkey = pubkey;
            return this;
        }

        public Builder ref(String ref) {
            this.ref = ref;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder expired() {
            this.status = "expired";
            this.reason = "cash.expired";
            return this;
        }

        public Builder encryptedContent(String encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public CashCancelEvent build() {
            if (senderPubkey == null) {
                throw new IllegalStateException("senderPubkey is required");
            }
            if (ref == null) {
                throw new IllegalStateException("ref is required");
            }
            if (reason == null) {
                throw new IllegalStateException("reason is required");
            }

            List<BaseTag> tags = new ArrayList<>();

            // Recipient pubkey tag (if known)
            if (recipientPubkey != null) {
                tags.add(BaseTag.create("p", recipientPubkey.toString()));
            }

            // Ref tag
            tags.add(BaseTag.create("ref", ref));

            // Build the payload
            long timestamp = System.currentTimeMillis() / 1000;
            CashCancelPayload payload = CashCancelPayload.builder()
                    .ref(ref)
                    .status(status)
                    .reason(reason)
                    .ts(timestamp)
                    .build();

            // Create the event using Integer kind constructor
            GenericEvent event = new GenericEvent(senderPubkey, CashEventKind.CASH_CANCEL, tags, "");
            event.setCreatedAt(timestamp);

            // Set encrypted content
            if (encryptedContent != null) {
                event.setContent(encryptedContent);
            } else {
                event.setContent("");
            }

            return new CashCancelEvent(event, payload);
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
     * Get the cancellation reason code.
     */
    public String getReason() {
        return payload.getReason();
    }

    /**
     * Check if this is an expiry cancellation.
     */
    public boolean isExpired() {
        return "expired".equals(payload.getStatus()) || "cash.expired".equals(payload.getReason());
    }

    /**
     * Parse a CashCancelEvent from a GenericEvent and decrypted payload.
     */
    public static CashCancelEvent fromGenericEvent(GenericEvent event, CashCancelPayload payload) {
        if (event.getKind() != CashEventKind.CASH_CANCEL) {
            throw new IllegalArgumentException("Event kind must be " + CashEventKind.CASH_CANCEL);
        }
        return new CashCancelEvent(event, payload);
    }

    /**
     * Parse the payload from decrypted JSON content.
     */
    public static CashCancelPayload parsePayload(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, CashCancelPayload.class);
    }
}
