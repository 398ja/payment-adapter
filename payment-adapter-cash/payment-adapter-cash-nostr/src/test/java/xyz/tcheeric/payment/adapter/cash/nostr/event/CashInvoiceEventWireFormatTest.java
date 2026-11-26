package xyz.tcheeric.payment.adapter.cash.nostr.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import nostr.base.PublicKey;
import nostr.event.json.EventJsonMapper;
import org.junit.jupiter.api.Test;

/** Pins the CashInvoice event wire format that other services parse. */
class CashInvoiceEventWireFormatTest {

    private static final String MERCHANT_PUBKEY =
            "2bed79f81439ff794cf5ac5f7bff9121e257f399829e472c7a14d3e86fe76984";

    // The serialized invoice event carries the protocol version tag exactly as ["v","0.2"].
    @Test
    void serializedInvoiceCarriesExactVersionTag() throws Exception {
        JsonNode tags = serializedTags(anInvoice());

        assertEquals(List.of("v", "0.2"), tagStartingWith(tags, "v"));
    }

    // The serialized invoice event carries the invoice reference exactly as ["ref",<ref>].
    @Test
    void serializedInvoiceCarriesExactRefTag() throws Exception {
        JsonNode tags = serializedTags(anInvoice());

        assertEquals(List.of("ref", "inv-123"), tagStartingWith(tags, "ref"));
    }

    private static CashInvoiceEvent anInvoice() {
        return CashInvoiceEvent.builder()
                .merchantPubkey(new PublicKey(MERCHANT_PUBKEY))
                .amount(1000)
                .ref("inv-123")
                .expiresAt(1_900_000_000L)
                .addRelay("wss://relay.example")
                .build();
    }

    private static JsonNode serializedTags(CashInvoiceEvent invoice) throws Exception {
        String json = EventJsonMapper.getMapper().writeValueAsString(invoice.getEvent());
        return EventJsonMapper.getMapper().readTree(json).get("tags");
    }

    private static List<String> tagStartingWith(JsonNode tags, String code) {
        for (JsonNode tag : tags) {
            if (code.equals(tag.get(0).asText())) {
                List<String> values = new ArrayList<>();
                tag.forEach(element -> values.add(element.asText()));
                return values;
            }
        }
        throw new AssertionError("No tag with code " + code + " in " + tags);
    }
}
