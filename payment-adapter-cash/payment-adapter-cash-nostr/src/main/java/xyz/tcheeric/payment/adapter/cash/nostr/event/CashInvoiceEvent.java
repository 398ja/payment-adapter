package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.base.ElementAttribute;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashInvoicePayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CashInvoice event (kind 5200) for NIP-XX Cash Payments.
 * Merchant publishes this invoice to relays and displays as QR code.
 */
@Slf4j
@Getter
public class CashInvoiceEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenericEvent event;
    private final CashInvoicePayload payload;

    private CashInvoiceEvent(GenericEvent event, CashInvoicePayload payload) {
        this.event = event;
        this.payload = payload;
    }

    /**
     * Builder for creating CashInvoice events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PublicKey merchantPubkey;
        private Integer amount;
        private String fiat;
        private String ref;
        private Long expiresAt;
        private List<String> relayUrls = new ArrayList<>();
        private String memo;
        private String locationHash;
        private String encryptedContent;

        public Builder merchantPubkey(PublicKey pubkey) {
            this.merchantPubkey = pubkey;
            return this;
        }

        public Builder amount(Integer amount) {
            this.amount = amount;
            return this;
        }

        public Builder fiat(String fiat) {
            this.fiat = fiat;
            return this;
        }

        public Builder ref(String ref) {
            this.ref = ref;
            return this;
        }

        public Builder expiresAt(Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt.getEpochSecond();
            return this;
        }

        public Builder addRelay(String relayUrl) {
            this.relayUrls.add(relayUrl);
            return this;
        }

        public Builder relays(List<String> relayUrls) {
            this.relayUrls = new ArrayList<>(relayUrls);
            return this;
        }

        public Builder memo(String memo) {
            this.memo = memo;
            return this;
        }

        public Builder locationHash(String hash) {
            this.locationHash = hash;
            return this;
        }

        public Builder encryptedContent(String encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public CashInvoiceEvent build() {
            if (merchantPubkey == null) {
                throw new IllegalStateException("merchantPubkey is required");
            }
            if (amount == null) {
                throw new IllegalStateException("amount is required");
            }
            if (ref == null) {
                throw new IllegalStateException("ref is required");
            }
            if (expiresAt == null) {
                throw new IllegalStateException("expiresAt is required");
            }
            if (relayUrls.isEmpty()) {
                throw new IllegalStateException("at least one relay URL is required");
            }

            List<BaseTag> tags = new ArrayList<>();

            // Amount tag
            tags.add(BaseTag.create("amount", String.valueOf(amount)));

            // Fiat tag (if not satoshis)
            if (fiat != null && !fiat.isEmpty()) {
                tags.add(BaseTag.create("fiat", fiat));
            }

            // Ref tag
            tags.add(BaseTag.create("ref", ref));

            // Expiry tag
            tags.add(BaseTag.create("exp", String.valueOf(expiresAt)));

            // Relay tags
            for (String relay : relayUrls) {
                tags.add(BaseTag.create("r", relay));
            }

            // Optional location hash
            if (locationHash != null && !locationHash.isEmpty()) {
                tags.add(BaseTag.create("h", locationHash));
            }

            // Version tag (use GenericTag directly to avoid VoteTag parseInt)
            tags.add(new GenericTag("v", new ElementAttribute("param0", "0.2")));

            // Build the payload
            CashInvoicePayload payload = CashInvoicePayload.builder()
                    .amount(amount)
                    .fiat(fiat)
                    .memo(memo)
                    .ref(ref)
                    .exp(expiresAt)
                    .enc("nip44")
                    .build();

            // Create the event using Integer kind constructor
            GenericEvent event = new GenericEvent(merchantPubkey, CashEventKind.CASH_INVOICE, tags, "");
            event.setCreatedAt(System.currentTimeMillis() / 1000);

            // Set content (encrypted payload or empty)
            if (encryptedContent != null) {
                event.setContent(encryptedContent);
            } else {
                event.setContent("");
            }

            return new CashInvoiceEvent(event, payload);
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
     * Get the amount from the event.
     */
    public Integer getAmount() {
        return payload.getAmount();
    }

    /**
     * Get the fiat currency code.
     */
    public String getFiat() {
        return payload.getFiat();
    }

    /**
     * Parse a CashInvoiceEvent from a GenericEvent.
     */
    public static CashInvoiceEvent fromGenericEvent(GenericEvent event, CashInvoicePayload payload) {
        if (event.getKind() != CashEventKind.CASH_INVOICE) {
            throw new IllegalArgumentException("Event kind must be " + CashEventKind.CASH_INVOICE);
        }
        return new CashInvoiceEvent(event, payload);
    }
}
