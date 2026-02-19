package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashDisputePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * CashDispute event (kind 5204) for NIP-XX Cash Payments.
 * Optional dispute record for manual review. OFF by default.
 * Uses long-term identity keys (not ephemeral) for accountability.
 */
@Slf4j
@Getter
public class CashDisputeEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;
    private final CashDisputePayload payload;

    private CashDisputeEvent(GenericEvent event, CashDisputePayload payload) {
        this.event = event;
        this.payload = payload;
    }

    /**
     * Builder for creating CashDispute events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PublicKey disputerPubkey;
        private PublicKey counterpartyPubkey;
        private String originalEventId;
        private String ref;
        private String claim;
        private String description;
        private String evidenceHash;
        private String encryptedContent;

        public Builder disputerPubkey(PublicKey pubkey) {
            this.disputerPubkey = pubkey;
            return this;
        }

        public Builder counterpartyPubkey(PublicKey pubkey) {
            this.counterpartyPubkey = pubkey;
            return this;
        }

        public Builder originalEventId(String eventId) {
            this.originalEventId = eventId;
            return this;
        }

        public Builder ref(String ref) {
            this.ref = ref;
            return this;
        }

        public Builder claim(String claim) {
            this.claim = claim;
            return this;
        }

        public Builder amountDispute() {
            this.claim = "amount_dispute";
            return this;
        }

        public Builder noReceipt() {
            this.claim = "no_receipt";
            return this;
        }

        public Builder fraud() {
            this.claim = "fraud";
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder evidenceHash(String hash) {
            this.evidenceHash = hash;
            return this;
        }

        public Builder encryptedContent(String encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public CashDisputeEvent build() {
            if (disputerPubkey == null) {
                throw new IllegalStateException("disputerPubkey is required");
            }
            if (ref == null) {
                throw new IllegalStateException("ref is required");
            }
            if (claim == null) {
                throw new IllegalStateException("claim is required");
            }

            List<BaseTag> tags = new ArrayList<>();

            // Counterparty pubkey tag
            if (counterpartyPubkey != null) {
                tags.add(BaseTag.create("p", counterpartyPubkey.toString()));
            }

            // Ref tag
            tags.add(BaseTag.create("ref", ref));

            // Original event reference
            if (originalEventId != null) {
                tags.add(BaseTag.create("e", originalEventId));
            }

            // Build the payload
            CashDisputePayload payload = CashDisputePayload.builder()
                    .ref(ref)
                    .claim(claim)
                    .description(description)
                    .evidenceHash(evidenceHash)
                    .build();

            // Create the event using Integer kind constructor
            GenericEvent event = new GenericEvent(disputerPubkey, CashEventKind.CASH_DISPUTE, tags, "");
            event.setCreatedAt(System.currentTimeMillis() / 1000);

            // Set encrypted content
            if (encryptedContent != null) {
                event.setContent(encryptedContent);
            } else {
                event.setContent("");
            }

            return new CashDisputeEvent(event, payload);
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
     * Get the dispute claim type.
     */
    public String getClaim() {
        return payload.getClaim();
    }

    /**
     * Parse a CashDisputeEvent from a GenericEvent and decrypted payload.
     */
    public static CashDisputeEvent fromGenericEvent(GenericEvent event, CashDisputePayload payload) {
        if (event.getKind() != CashEventKind.CASH_DISPUTE) {
            throw new IllegalArgumentException("Event kind must be " + CashEventKind.CASH_DISPUTE);
        }
        return new CashDisputeEvent(event, payload);
    }

    /**
     * Parse the payload from decrypted JSON content.
     */
    public static CashDisputePayload parsePayload(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, CashDisputePayload.class);
    }
}
