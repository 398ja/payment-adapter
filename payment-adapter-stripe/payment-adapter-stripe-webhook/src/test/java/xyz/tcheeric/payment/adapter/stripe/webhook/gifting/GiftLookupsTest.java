package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The handle resolver's per-environment override (imani-wallet#410 G14 local run). */
class GiftLookupsTest {

    @Test
    void overridesParseStrictlyAndBlankIsNone() {
        assertTrue(GiftLookups.WellKnownResolver.parseOverrides("").isEmpty());
        assertTrue(GiftLookups.WellKnownResolver.parseOverrides(null).isEmpty());
        assertEquals(Map.of("imani.local", "http://imani.local:8085"),
                GiftLookups.WellKnownResolver.parseOverrides(" imani.local=http://imani.local:8085 "));
        for (String bad : new String[] {"imani.local", "=http://x", "imani.local=ftp://x", "imani.local=http://x/path",
                "IMANI.LOCAL=http://x", "a=http://x,b"}) {
            assertThrows(IllegalArgumentException.class, () -> GiftLookups.WellKnownResolver.parseOverrides(bad), bad);
        }
    }

    @Test
    void anOverriddenDomainIsAskedAtItsBaseAndOthersStayOnHttps() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> asked = new AtomicReference<>();
        server.createContext("/.well-known/nostr.json", ex -> {
            asked.set(ex.getRequestURI().toString());
            byte[] body = ("{\"names\":{\"alice\":\"" + "ab".repeat(32) + "\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GiftLookups.WellKnownResolver resolver = new GiftLookups.WellKnownResolver(Map.of("imani.local", base));
            assertEquals(Optional.of("ab".repeat(32)), resolver.resolve("alice@imani.local"));
            assertEquals("/.well-known/nostr.json?name=alice", asked.get());
            // A domain with no override is never sent to the override's server.
            asked.set(null);
            resolver.resolve("alice@invalid.example");
            assertEquals(null, asked.get());
        } finally {
            server.stop(0);
        }
    }
}
