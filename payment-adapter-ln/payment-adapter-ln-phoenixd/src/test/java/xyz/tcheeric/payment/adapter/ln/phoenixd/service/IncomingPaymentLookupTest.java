package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two HTTP edges of the lost-webhook reconcile, against a real HTTP server: phoenixd's
 * incoming-payment lookup, and the replay of phoenixd's callback to the adapter's webhook.
 *
 * <p>Uses the JDK's own server: this module's WireMock has no Jetty on its classpath.
 */
class IncomingPaymentLookupTest {

    private HttpServer server;
    private String baseUrl;
    private String previousBaseUrl;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        previousBaseUrl = System.getProperty("phoenixd.base_url");
        System.setProperty("phoenixd.base_url", baseUrl);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (previousBaseUrl == null) {
            System.clearProperty("phoenixd.base_url");
        } else {
            System.setProperty("phoenixd.base_url", previousBaseUrl);
        }
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
    }

    // phoenixd answers GET /payments/incoming/{hash} with its record of the payment. The lookup
    // must hit that path and read isPaid, ignoring the many fields the adapter does not use (the
    // library's parser would otherwise fail on them).
    @Test
    void readsAPaidIncomingPayment() {
        respond("/payments/incoming/abc123", 200,
                "{\"paymentHash\":\"abc123\",\"preimage\":\"pre\",\"externalId\":\"q-1\","
                        + "\"isPaid\":true,\"receivedSat\":30,\"fees\":0,\"completedAt\":1,\"createdAt\":0}");

        IncomingPaymentResponse response = new PhoenixdServiceImpl().getIncomingPayment("abc123");

        assertEquals("/payments/incoming/abc123", lastPath.get());
        assertTrue(response.getPaid());
        assertEquals(30L, response.getReceivedSat());
        assertEquals("abc123", response.getPaymentHash());
    }

    // A 404 is phoenixd saying it holds no payment for the hash: a definite "not paid", returned
    // as null rather than an error.
    @Test
    void aMissingPaymentIsNull() {
        respond("/payments/incoming/nope", 404, "");

        assertNull(new PhoenixdServiceImpl().getIncomingPayment("nope"));
    }

    // A 5xx is not an answer about the payment, so it must throw (the gateway reads it as unknown).
    @Test
    void aServerErrorThrows() {
        respond("/payments/incoming/boom", 503, "");

        assertThrows(RuntimeException.class, () -> new PhoenixdServiceImpl().getIncomingPayment("boom"));
    }

    // An unreachable phoenixd must throw too, never read as "no payment".
    @Test
    void anUnreachableNodeThrows() {
        server.stop(0);

        assertThrows(RuntimeException.class, () -> new PhoenixdServiceImpl().getIncomingPayment("down"));
    }

    // The relay replays phoenixd's own callback shape (form-encoded type, amountSat, paymentHash,
    // externalId) so the adapter's webhook handler processes it exactly as the lost original.
    @Test
    void relayPostsPhoenixdsCallbackShapeAndReportsSuccess() throws Exception {
        respond("/webhook/phoenixd", 200, "");

        boolean relayed = new AdapterWebhookRelay().relayPaymentReceived(webhook(), "q-1", 30, "abc123");

        assertTrue(relayed);
        assertEquals("application/x-www-form-urlencoded", lastContentType.get());
        assertEquals("type=payment_received&amountSat=30&paymentHash=abc123&externalId=q-1", lastBody.get());
    }

    // A refused replay is reported as not delivered, so the caller can raise its alert.
    @Test
    void relayReportsARefusal() throws Exception {
        respond("/webhook/phoenixd", 500, "");

        assertFalse(new AdapterWebhookRelay().relayPaymentReceived(webhook(), "q-1", 30, "abc123"));
    }

    private URL webhook() throws Exception {
        return URI.create(baseUrl + "/webhook/phoenixd").toURL();
    }
}
