package xyz.tcheeric.payment.adapter.core.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the client can actually send a PATCH over a real socket.
 *
 * <p><strong>Why this exists as a separate test from {@link QuoteClientTest}.</strong> That suite
 * uses {@code MockRestServiceServer}, which REPLACES the RestTemplate's request factory with its
 * own recorder. So it asserts the request the code intended to send, not the request the
 * transport can actually produce — and those differ here in the way that matters.
 *
 * <p>Checked rather than assumed: reverting {@link AbstractBaseClient} to a plain
 * {@code new RestTemplate()} leaves every assertion in {@code QuoteClientTest} green, because the
 * mock never exercises {@code HttpURLConnection}. Against a real server the same code throws
 *
 * <pre>java.net.ProtocolException: Invalid HTTP method: PATCH</pre>
 *
 * <p>That combination is the dangerous one, and it is precisely the shape of the bug this whole
 * change is about (#245): the caller, {@code PhoenixWebhookHandler.recordMintNotified}, catches
 * {@code RuntimeException} and logs a warning. So a stamp that could never be sent would have
 * failed silently on every payment while the unit tests reported success — a fix for a silent
 * failure, failing silently.
 *
 * <p>A real loopback server is the cheapest thing that can tell the difference. No container, no
 * network, no fixture: one handler that records the method it was given.
 */
class QuoteClientPatchTransportTest {

    private HttpServer server;
    private final AtomicReference<String> observedMethod = new AtomicReference<>();
    private final AtomicReference<String> observedBody = new AtomicReference<>();

    private String previousBaseUrl;
    private String previousPort;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quote/9", exchange -> {
            observedMethod.set(exchange.getRequestMethod());
            observedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] body = "{\"id\":9,\"state\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        // QuoteClient resolves its base URL from system properties, so the ephemeral port has to
        // be published before the client is constructed. Saved and restored so this test cannot
        // leak configuration into whatever runs next in the same JVM.
        previousBaseUrl = System.getProperty("gateway.api.base_url");
        previousPort = System.getProperty("gateway.api.port");
        System.setProperty("gateway.api.base_url", "http://127.0.0.1");
        System.setProperty("gateway.api.port", String.valueOf(server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        restore("gateway.api.base_url", previousBaseUrl);
        restore("gateway.api.port", previousPort);
        server.stop(0);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("stampMintNotified reaches a real server as a PATCH, carrying only its own field")
    void sendsARealPatchOverTheWire() {
        QuoteClient client = new QuoteClient();

        client.stampMintNotified(9L, Instant.parse("2026-09-23T16:32:37Z"));

        assertThat(observedMethod.get())
                .as("the default RestTemplate factory cannot send PATCH at all; if this reads "
                        + "POST or the call threw, the stamp would fail silently in production")
                .isEqualTo("PATCH");
        assertThat(observedBody.get())
                .as("the stamp must not carry state — writing it back is what reverted a settled "
                        + "payment to PENDING and cost three sales")
                .contains("mintNotifiedAt")
                .doesNotContain("state");
    }
}
