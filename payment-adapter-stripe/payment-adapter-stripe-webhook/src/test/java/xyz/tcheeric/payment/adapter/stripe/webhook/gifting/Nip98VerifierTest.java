package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;

/** NIP-98 as the gifting page sends it through the wallet's cross-origin signer (#410 §4a). */
class Nip98VerifierTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String URL = "https://gifting.imani.casa/api/v1/gifting/drafts";
    static final long NOW = 1_800_000_000L;
    static final byte[] SK = HexFormat.of().parseHex("7f".repeat(32));

    final Nip98Verifier verifier = new Nip98Verifier(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    static String header(String url, String method, byte[] body, long createdAt, int kind) throws Exception {
        String pubkey = HexFormat.of().formatHex(Schnorr.genPubKey(SK));
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(url));
        tags.add(MAPPER.createArrayNode().add("method").add(method));
        if (body != null) {
            tags.add(MAPPER.createArrayNode().add("payload")
                    .add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))));
        }
        ArrayNode canonical = MAPPER.createArrayNode().add(0).add(pubkey).add(createdAt).add(kind);
        canonical.add(tags);
        canonical.add("");
        byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey);
        event.put("created_at", createdAt);
        event.put("kind", kind);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", HexFormat.of().formatHex(Schnorr.sign(id, SK, new byte[32])));
        return "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
    }

    static String pubkey() throws Exception {
        return HexFormat.of().formatHex(Schnorr.genPubKey(SK));
    }

    @Test
    void acceptsAGoodEventAndAnswersItsSigner() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of(pubkey()), verifier.verify(header(URL, "POST", body, NOW, 27235), URL, "POST", body));
    }

    @Test
    void refusesAnotherUrlMethodBodyKindOrAStaleEvent() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertTrue(verifier.verify(header("https://evil.example/api/v1/gifting/drafts", "POST", body, NOW, 27235), URL, "POST", body).isEmpty());
        assertTrue(verifier.verify(header(URL, "GET", body, NOW, 27235), URL, "POST", body).isEmpty());
        assertTrue(verifier.verify(header(URL, "POST", body, NOW, 27235), URL, "POST", "{\"a\":2}".getBytes()).isEmpty());
        assertTrue(verifier.verify(header(URL, "POST", body, NOW, 1), URL, "POST", body).isEmpty());
        assertTrue(verifier.verify(header(URL, "POST", body, NOW - 120, 27235), URL, "POST", body).isEmpty());
    }

    @Test
    void refusesATamperedSignatureAndGarbage() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String good = header(URL, "POST", body, NOW, 27235);
        ObjectNode event = (ObjectNode) MAPPER.readTree(Base64.getDecoder().decode(good.substring(6)));
        event.put("sig", "00".repeat(64));
        String bad = "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
        assertTrue(verifier.verify(bad, URL, "POST", body).isEmpty());
        assertTrue(verifier.verify("Nostr !!!", URL, "POST", body).isEmpty());
        assertTrue(verifier.verify("Bearer x", URL, "POST", body).isEmpty());
        assertTrue(verifier.verify(null, URL, "POST", body).isEmpty());
    }
}
