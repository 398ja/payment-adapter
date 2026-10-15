package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2: the NIP-98 header must verify the way the gateways verify it.
 *
 * <p>imani-security's {@code Nip98AuthFilter} (used by gateway-core and
 * gateway-customer) runs BIP-340 {@code Schnorr.verify(id, xOnlyPubkey, sig)}
 * over the raw 32-byte event id, where the id is NIP-01's sha256 of
 * {@code [0,pubkey,created_at,kind,tags,content]}. nostr-java's
 * {@code Identity.sign} signs sha256(bytes) instead, so it must not be used for
 * a precomputed id.
 */
class GatewayFulfilmentSignatureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String SERVICE_KEY = "3".repeat(64);

    private HttpServer server;
    private final List<String> headers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] out = "{\"fulfilled\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getAndPutHeadersVerifyAsTheGatewayVerifiesThem() throws Exception {
        GatewayFulfilmentClient client = new GatewayFulfilmentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), SERVICE_KEY, Duration.ofSeconds(5));

        client.check("pr_0123456789abcdef");
        client.register("pr_fedcba9876543210", "a".repeat(64), "issuer-1", 2500L, "gbp");

        assertEquals(2, headers.size(), "both GET and PUT must reach the gateway");
        for (String header : headers) {
            assertTrue(header.startsWith("Nostr "));
            JsonNode event = MAPPER.readTree(Base64.getDecoder().decode(header.substring("Nostr ".length())));
            assertGatewayAccepts(event);
            assertEquals(HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(SERVICE_KEY))), event.get("pubkey").asText());
        }
    }

    /** A vector produced by nostr-tools 2.23.3 finalizeEvent with the same key. */
    @Test
    void nostrToolsVectorPassesTheSameVerifier() throws Exception {
        JsonNode event = MAPPER.readTree("""
                {"kind":27235,"created_at":1760000000,"tags":[["u","http://127.0.0.1/api/v1/atomic/fulfilment/pr_\\"q\\\\1"],["method","GET"]],"content":"","pubkey":"3c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1","id":"88c4e4e7170683bd1b78206d21fb574e9c13f9607bd49e429c0e2901920a0c13","sig":"05931f5f4b2e9934084c5c9b557fda038b7b6f7b94af2b6a31438f18c312ef06c9ae94d379c5293434f3378b34f7b6cac4a41e991c410b911c561902ccd59187"}
                """);
        assertEquals(HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(SERVICE_KEY))), event.get("pubkey").asText());
        assertGatewayAccepts(event);
    }

    private static void assertGatewayAccepts(JsonNode event) throws Exception {
        ArrayNode canonical = MAPPER.createArrayNode();
        canonical.add(0);
        canonical.add(event.get("pubkey").asText());
        canonical.add(event.get("created_at").asLong());
        canonical.add(event.get("kind").asInt());
        canonical.add(event.get("tags"));
        canonical.add(event.get("content").asText());
        byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(canonical));
        assertEquals(HEX.formatHex(id), event.get("id").asText(), "NIP-01 id");

        byte[] pub = HEX.parseHex(event.get("pubkey").asText());
        byte[] sig = HEX.parseHex(event.get("sig").asText());
        assertEquals(32, pub.length);
        assertEquals(64, sig.length);
        assertTrue(Schnorr.verify(id, pub, sig), "BIP-340 over the raw event id");
        assertFalse(Schnorr.verify(MessageDigest.getInstance("SHA-256").digest(id), pub, sig),
                "must not be a signature over sha256(id)");
    }
}
