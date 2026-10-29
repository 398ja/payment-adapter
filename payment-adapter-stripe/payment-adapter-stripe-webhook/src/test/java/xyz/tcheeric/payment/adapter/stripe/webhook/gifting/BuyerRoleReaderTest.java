package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;

/** The buyer-role rule (imani-wallet#410): a live, SIGNED imani:merchant record makes a stall. */
class BuyerRoleReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String REAL_SIG = "97084e00fc9b6dbef3439c04a61f993b0bbf0f397a473ca1bfcd835a055c4fe3c8b7be1285f42df7c1ea9c3d576fd5a2246ba64ee5411ec2c9ec32b50dd5928a";

    private final byte[] sk = new byte[32];
    private final String pk;

    BuyerRoleReaderTest() throws Exception {
        new SecureRandom().nextBytes(sk);
        pk = HEX.formatHex(Schnorr.genPubKey(sk));
    }

    private ObjectNode record(String content, long createdAt, String d, byte[] key) throws Exception {
        String author = HEX.formatHex(Schnorr.genPubKey(key));
        ArrayNode tags = MAPPER.createArrayNode();
        tags.addArray().add("d").add(d);
        ArrayNode canonical = MAPPER.createArrayNode();
        canonical.add(0).add(author).add(createdAt).add(30078).add(tags).add(content);
        byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        byte[] aux = new byte[32];
        new SecureRandom().nextBytes(aux);
        ObjectNode e = MAPPER.createObjectNode();
        e.put("id", HEX.formatHex(id));
        e.put("pubkey", author);
        e.put("createdAt", createdAt);
        e.put("kind", 30078);
        e.set("tags", tags);
        e.put("content", content);
        e.put("sig", HEX.formatHex(Schnorr.sign(id, key, aux)));
        return e;
    }

    private JsonNode list(JsonNode... events) {
        ArrayNode a = MAPPER.createArrayNode();
        for (JsonNode e : events) {
            a.add(e);
        }
        return a;
    }

    // G17: the validity a claim-by date is computed from, read as purchase-issuer reads it.
    @Test
    void theValidityIsReadFromTheNewestSignedRecordAsTheIssuerReadsIt() throws Exception {
        assertEquals(java.util.OptionalInt.empty(), BuyerRoleReader.validityFrom(list(), pk));
        assertEquals(java.util.OptionalInt.of(30), BuyerRoleReader.validityFrom(list(
                record("{\"voucherValidityDays\":90}", 100, "imani:merchant", sk),
                record("{\"voucherValidityDays\":30}", 200, "imani:merchant", sk)), pk));
        assertEquals(java.util.OptionalInt.of(45), BuyerRoleReader.validityFrom(list(record("{\"voucherValidityDays\":\" 45 \"}", 1, "imani:merchant", sk)), pk));
        // Anything not a validity is the default, never-expires (validityFromRecord).
        for (String bad : new String[] {"{}", "{\"voucherValidityDays\":false}", "{\"voucherValidityDays\":731}",
                "{\"voucherValidityDays\":1.5}", "{\"voucherValidityDays\":\"0x10\"}", "not json"}) {
            assertEquals(java.util.OptionalInt.of(0), BuyerRoleReader.validityFrom(list(record(bad, 1, "imani:merchant", sk)), pk), bad);
        }
        // Someone else's record says nothing about this stall.
        assertEquals(java.util.OptionalInt.empty(), BuyerRoleReader.validityFrom(list(record("{\"voucherValidityDays\":30}", 1, "imani:profile", sk)), pk));
    }

    @Test
    void noRecordIsACustomer() {
        assertEquals(BuyerRoleReader.Standing.CUSTOMER, BuyerRoleReader.standingFrom(list(), pk));
    }

    @Test
    void aLiveSignedRecordIsAStall() throws Exception {
        assertEquals(BuyerRoleReader.Standing.MERCHANT,
                BuyerRoleReader.standingFrom(list(record("{\"active\":true}", 100, "imani:merchant", sk)), pk));
    }

    @Test
    void theNewestRecordWinsAndOnlyAnExplicitFalseRetires() throws Exception {
        assertEquals(BuyerRoleReader.Standing.CUSTOMER, BuyerRoleReader.standingFrom(list(
                record("{\"active\":true}", 100, "imani:merchant", sk),
                record("{\"active\":false}", 200, "imani:merchant", sk)), pk));
        assertEquals(BuyerRoleReader.Standing.MERCHANT, BuyerRoleReader.standingFrom(list(
                record("not json", 100, "imani:merchant", sk)), pk));
    }

    @Test
    void aRecordSomeoneElseSignedOrTamperedWithDoesNotCount() throws Exception {
        byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        ObjectNode forged = record("{\"active\":true}", 100, "imani:merchant", other);
        forged.put("pubkey", pk);
        assertEquals(BuyerRoleReader.Standing.CUSTOMER, BuyerRoleReader.standingFrom(list(forged), pk));

        ObjectNode tampered = record("{\"active\":true}", 100, "imani:merchant", sk);
        tampered.put("content", "{\"active\":true,\"x\":1}");
        assertEquals(BuyerRoleReader.Standing.CUSTOMER, BuyerRoleReader.standingFrom(list(tampered), pk));

        assertEquals(BuyerRoleReader.Standing.CUSTOMER,
                BuyerRoleReader.standingFrom(list(record("{\"active\":true}", 100, "imani:profile", sk)), pk));
    }

    @Test
    void readsARecordTheWalletSignedAsTheGatewayServesIt() throws Exception {
        // Captured from a local gateway-customer's POST /api/v1/nostr/query (2026-10-28): a stall
        // record the WALLET signed with nostr-tools, served in the gateway's shape (`createdAt`).
        // Pins that Java computes the same event id over the same JSON as the wallet does.
        String served = "{\"id\":\"6477338a076fabc4a3f74899cd721bc4adc7fc47156d254432762d13ac1a525d\","
                + "\"pubkey\":\"7863b339e8a3661e3b91ad431735159a96ddb4218fb34b32a200461cab143388\",\"createdAt\":1793218201,"
                + "\"kind\":30078,\"tags\":[[\"d\",\"imani:merchant\"]],"
                + "\"content\":\"{\\\"active\\\":true,\\\"categories\\\":[\\\"food\\\"],\\\"issuanceCurrency\\\":\\\"EUR\\\",\\\"voucherValidityDays\\\":0}\","
                + "\"sig\":\"SIG\"}";
        JsonNode event = MAPPER.readTree(served.replace("SIG", REAL_SIG));
        assertEquals(BuyerRoleReader.Standing.MERCHANT,
                BuyerRoleReader.standingFrom(list(event), "7863b339e8a3661e3b91ad431735159a96ddb4218fb34b32a200461cab143388"));
    }

    @Test
    void noGatewayConfiguredIsUnknownNeverCustomer() {
        assertEquals(BuyerRoleReader.Standing.UNKNOWN, new BuyerRoleReader("", java.time.Duration.ofSeconds(1)).standingOf(pk));
    }

    private ObjectNode profile(String content, long createdAt, byte[] key) throws Exception {
        String author = HEX.formatHex(Schnorr.genPubKey(key));
        ArrayNode tags = MAPPER.createArrayNode();
        ArrayNode canonical = MAPPER.createArrayNode();
        canonical.add(0).add(author).add(createdAt).add(0).add(tags).add(content);
        byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        ObjectNode e = MAPPER.createObjectNode();
        e.put("id", HEX.formatHex(id));
        e.put("pubkey", author);
        e.put("created_at", createdAt);
        e.put("kind", 0);
        e.set("tags", tags);
        e.put("content", content);
        e.put("sig", HEX.formatHex(Schnorr.sign(id, key, new byte[32])));
        return e;
    }

    // #410 G15: the return-link email names the stall as its page does (kind-0 name), never a handle.
    @Test
    void theStallsDisplayNameIsItsNewestSignedProfileName() throws Exception {
        assertEquals(java.util.Optional.of("Card Test Bakery"), BuyerRoleReader.displayNameFrom(list(
                profile("{\"name\":\"Old Name\"}", 100, sk),
                profile("{\"name\":\"Card Test Bakery\"}", 200, sk)), pk));
        assertEquals(java.util.Optional.of("Shown"), BuyerRoleReader.displayNameFrom(list(profile("{\"display_name\":\"Shown\"}", 1, sk)), pk));
        assertEquals(java.util.Optional.of("Two lines"), BuyerRoleReader.displayNameFrom(list(profile("{\"name\":\"Two\\nlines\"}", 1, sk)), pk));
    }

    @Test
    void aProfileSomeoneElseSignedOrNoNameGivesNothing() throws Exception {
        byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        ObjectNode forged = profile("{\"name\":\"Evil\"}", 300, other);
        forged.put("pubkey", pk);
        assertEquals(java.util.Optional.empty(), BuyerRoleReader.displayNameFrom(list(forged), pk));
        assertEquals(java.util.Optional.empty(), BuyerRoleReader.displayNameFrom(list(profile("{}", 1, sk)), pk));
        assertEquals(java.util.Optional.empty(), BuyerRoleReader.displayNameFrom(list(), pk));
    }
}
