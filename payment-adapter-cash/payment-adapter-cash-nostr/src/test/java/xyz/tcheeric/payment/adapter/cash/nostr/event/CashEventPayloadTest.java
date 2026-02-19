package xyz.tcheeric.payment.adapter.cash.nostr.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.*;

import static org.junit.jupiter.api.Assertions.*;

public class CashEventPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Verifies CashInvoicePayload serializes to JSON with correct fields
    @Test
    void testInvoicePayloadSerialization() throws Exception {
        CashInvoicePayload payload = CashInvoicePayload.builder()
                .amount(1500)
                .fiat("USD")
                .memo("espresso")
                .ref("6f2c1d")
                .exp(1712345900L)
                .enc("nip44")
                .build();

        String json = MAPPER.writeValueAsString(payload);

        assertTrue(json.contains("\"amount\":1500"));
        assertTrue(json.contains("\"fiat\":\"USD\""));
        assertTrue(json.contains("\"memo\":\"espresso\""));
        assertTrue(json.contains("\"ref\":\"6f2c1d\""));
        assertTrue(json.contains("\"exp\":1712345900"));
        assertTrue(json.contains("\"enc\":\"nip44\""));
    }

    // Verifies CashInvoicePayload deserializes from JSON
    @Test
    void testInvoicePayloadDeserialization() throws Exception {
        String json = """
            {"amount":1500,"fiat":"USD","memo":"espresso","ref":"6f2c1d","exp":1712345900,"enc":"nip44"}
            """;

        CashInvoicePayload payload = MAPPER.readValue(json, CashInvoicePayload.class);

        assertEquals(1500, payload.getAmount());
        assertEquals("USD", payload.getFiat());
        assertEquals("espresso", payload.getMemo());
        assertEquals("6f2c1d", payload.getRef());
        assertEquals(1712345900L, payload.getExp());
        assertEquals("nip44", payload.getEnc());
    }

    // Verifies null fields are omitted from JSON per @JsonInclude(NON_NULL)
    @Test
    void testInvoicePayloadOmitsNulls() throws Exception {
        CashInvoicePayload payload = CashInvoicePayload.builder()
                .amount(21000)
                .ref("aabb11")
                .exp(1700000000L)
                .build();

        String json = MAPPER.writeValueAsString(payload);

        assertFalse(json.contains("fiat"));
        assertFalse(json.contains("memo"));
        assertFalse(json.contains("enc"));
    }

    // Verifies CashIntentPayload round-trip
    @Test
    void testIntentPayloadRoundTrip() throws Exception {
        CashIntentPayload payload = CashIntentPayload.builder()
                .ref("6f2c1d")
                .from("03abc123")
                .proof("4821")
                .ts(1712345650L)
                .build();

        String json = MAPPER.writeValueAsString(payload);
        CashIntentPayload parsed = MAPPER.readValue(json, CashIntentPayload.class);

        assertEquals("6f2c1d", parsed.getRef());
        assertEquals("03abc123", parsed.getFrom());
        assertEquals("4821", parsed.getProof());
        assertEquals(1712345650L, parsed.getTs());
    }

    // Verifies CashIntentEvent.parsePayload works
    @Test
    void testIntentEventParsePayload() throws Exception {
        String json = """
            {"ref":"6f2c1d","from":"03abc123","proof":"4821","ts":1712345650}
            """;

        CashIntentPayload payload = CashIntentEvent.parsePayload(json);

        assertEquals("6f2c1d", payload.getRef());
        assertEquals("03abc123", payload.getFrom());
    }

    // Verifies CashReceiptPayload factory and serialization
    @Test
    void testReceiptPayloadPaid() throws Exception {
        CashReceiptPayload payload = CashReceiptPayload.paid("6f2c1d", 1500);

        assertEquals("6f2c1d", payload.getRef());
        assertEquals("paid", payload.getStatus());
        assertEquals(1500, payload.getAmountReceived());
        assertNotNull(payload.getTs());

        String json = MAPPER.writeValueAsString(payload);
        assertTrue(json.contains("\"status\":\"paid\""));
    }

    // Verifies CashReceiptPayload deserialization
    @Test
    void testReceiptPayloadDeserialization() throws Exception {
        String json = """
            {"ref":"6f2c1d","status":"paid","ts":1712345700,"amount_received":1500}
            """;

        CashReceiptPayload payload = MAPPER.readValue(json, CashReceiptPayload.class);

        assertEquals("6f2c1d", payload.getRef());
        assertEquals("paid", payload.getStatus());
        assertEquals(1712345700L, payload.getTs());
        assertEquals(1500, payload.getAmountReceived());
    }

    // Verifies CashCancelPayload round-trip
    @Test
    void testCancelPayloadRoundTrip() throws Exception {
        CashCancelPayload payload = CashCancelPayload.builder()
                .ref("6f2c1d")
                .status("cancelled")
                .reason("cash.expired")
                .ts(1712345800L)
                .build();

        String json = MAPPER.writeValueAsString(payload);
        CashCancelPayload parsed = MAPPER.readValue(json, CashCancelPayload.class);

        assertEquals("6f2c1d", parsed.getRef());
        assertEquals("cancelled", parsed.getStatus());
        assertEquals("cash.expired", parsed.getReason());
        assertEquals(1712345800L, parsed.getTs());
    }

    // Verifies CashDisputePayload round-trip
    @Test
    void testDisputePayloadRoundTrip() throws Exception {
        CashDisputePayload payload = CashDisputePayload.builder()
                .ref("6f2c1d")
                .claim("amount_dispute")
                .description("Customer claims $20 paid")
                .evidenceHash("sha256:abc123")
                .build();

        String json = MAPPER.writeValueAsString(payload);
        CashDisputePayload parsed = MAPPER.readValue(json, CashDisputePayload.class);

        assertEquals("6f2c1d", parsed.getRef());
        assertEquals("amount_dispute", parsed.getClaim());
        assertEquals("Customer claims $20 paid", parsed.getDescription());
        assertEquals("sha256:abc123", parsed.getEvidenceHash());
    }

    // Verifies CashEventKind constants match spec
    @Test
    void testEventKindConstants() {
        assertEquals(5200, CashEventKind.CASH_INVOICE);
        assertEquals(5201, CashEventKind.CASH_INTENT);
        assertEquals(5202, CashEventKind.CASH_RECEIPT);
        assertEquals(5203, CashEventKind.CASH_CANCEL);
        assertEquals(5204, CashEventKind.CASH_DISPUTE);
    }
}
