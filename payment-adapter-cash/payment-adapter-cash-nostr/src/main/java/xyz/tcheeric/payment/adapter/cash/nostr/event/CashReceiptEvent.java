package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashReceiptPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * CashReceipt event (kind 5202) for NIP-XX Cash Payments.
 * Merchant publishes this after receiving physical cash.
 */
@Slf4j
@Getter
public class CashReceiptEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;
    private final CashReceiptPayload payload;

    private CashReceiptEvent(GenericEvent event, CashReceiptPayload payload) {
        this.event = event;
        this.payload = payload;
    }

    /**
     * Builder for creating CashReceipt events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PublicKey merchantPubkey;
        private PublicKey customerPubkey;
        private String ref;
        private Integer amountReceived;
        private String encryptedContent;

        public Builder merchantPubkey(PublicKey pubkey) {
            this.merchantPubkey = pubkey;
            return this;
        }

        public Builder customerPubkey(PublicKey pubkey) {
            this.customerPubkey = pubkey;
            return this;
        }

        public Builder ref(String ref) {
            this.ref = ref;
            return this;
        }

        public Builder amountReceived(Integer amount) {
            this.amountReceived = amount;
            return this;
        }

        public Builder encryptedContent(String encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public CashReceiptEvent build() {
            if (merchantPubkey == null) {
                throw new IllegalStateException("merchantPubkey is required");
            }
            if (customerPubkey == null) {
                throw new IllegalStateException("customerPubkey is required");
            }
            if (ref == null) {
                throw new IllegalStateException("ref is required");
            }
            if (amountReceived == null) {
                throw new IllegalStateException("amountReceived is required");
            }

            List<BaseTag> tags = new ArrayList<>();

            // Recipient pubkey tag (customer's ephemeral key)
            tags.add(BaseTag.create("p", customerPubkey.toString()));

            // Ref tag
            tags.add(BaseTag.create("ref", ref));

            // Build the payload
            CashReceiptPayload payload = CashReceiptPayload.paid(ref, amountReceived);

            // Create the event using Integer kind constructor
            GenericEvent event = new GenericEvent(merchantPubkey, CashEventKind.CASH_RECEIPT, tags, "");
            event.setCreatedAt(payload.getTs());

            // Set encrypted content
            if (encryptedContent != null) {
                event.setContent(encryptedContent);
            } else {
                event.setContent("");
            }

            return new CashReceiptEvent(event, payload);
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
     * Get the amount received.
     */
    public Integer getAmountReceived() {
        return payload.getAmountReceived();
    }

    /**
     * Parse a CashReceiptEvent from a GenericEvent and decrypted payload.
     */
    public static CashReceiptEvent fromGenericEvent(GenericEvent event, CashReceiptPayload payload) {
        if (event.getKind() != CashEventKind.CASH_RECEIPT) {
            throw new IllegalArgumentException("Event kind must be " + CashEventKind.CASH_RECEIPT);
        }
        return new CashReceiptEvent(event, payload);
    }

    /**
     * Parse the payload from decrypted JSON content.
     */
    public static CashReceiptPayload parsePayload(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, CashReceiptPayload.class);
    }
}
