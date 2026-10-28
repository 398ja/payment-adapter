package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;

/**
 * NIP-98 for the one gifting call that needs a buyer's identity: a "to myself" draft
 * (imani-wallet#410 §4a). The event is signed on the WALLET origin through the cross-origin
 * signer (NAP 0002) and addressed to this request's URL.
 *
 * <p>Checks: kind 27235, {@code u} equals the request URL, {@code method} matches, {@code payload}
 * is sha256 of the exact body, {@code created_at} within {@link #WINDOW_SECONDS}, the id is
 * NIP-01's hash, and a BIP-340 signature over the raw id. Answers the signer's pubkey, or empty.
 */
public class Nip98Verifier {

    static final long WINDOW_SECONDS = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;

    public Nip98Verifier() {
        this(Clock.systemUTC());
    }

    public Nip98Verifier(Clock clock) {
        this.clock = clock;
    }

    public Optional<String> verify(String authorization, String url, String method, byte[] body) {
        if (authorization == null || !authorization.startsWith("Nostr ")) {
            return Optional.empty();
        }
        try {
            JsonNode event = MAPPER.readTree(Base64.getDecoder().decode(authorization.substring(6).trim()));
            if (event.path("kind").asInt() != 27235) {
                return Optional.empty();
            }
            long createdAt = event.path("created_at").asLong();
            if (Math.abs(clock.instant().getEpochSecond() - createdAt) > WINDOW_SECONDS) {
                return Optional.empty();
            }
            String pubkey = event.path("pubkey").asText("");
            if (!pubkey.matches("^[0-9a-f]{64}$")) {
                return Optional.empty();
            }
            String u = tag(event, "u");
            String m = tag(event, "method");
            if (u == null || !u.equals(url) || m == null || !m.equalsIgnoreCase(method)) {
                return Optional.empty();
            }
            if (body != null && body.length > 0) {
                String payload = tag(event, "payload");
                String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                if (payload == null || !payload.equalsIgnoreCase(expected)) {
                    return Optional.empty();
                }
            }
            ArrayNode canonical = MAPPER.createArrayNode();
            canonical.add(0);
            canonical.add(pubkey);
            canonical.add(createdAt);
            canonical.add(27235);
            canonical.add(event.path("tags"));
            canonical.add(event.path("content").asText(""));
            byte[] id = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            if (!HexFormat.of().formatHex(id).equalsIgnoreCase(event.path("id").asText(""))) {
                return Optional.empty();
            }
            byte[] sig = HexFormat.of().parseHex(event.path("sig").asText(""));
            return Schnorr.verify(id, HexFormat.of().parseHex(pubkey), sig) ? Optional.of(pubkey) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String tag(JsonNode event, String name) {
        for (JsonNode t : event.path("tags")) {
            if (t.isArray() && t.size() >= 2 && name.equals(t.get(0).asText())) {
                return t.get(1).asText();
            }
        }
        return null;
    }
}
