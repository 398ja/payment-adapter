package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeCheckoutCreationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * payment-adapter#274 (card atomicity audit S9, G8): a Stripe outage at checkout held the buyer
 * for the SDK's whole retry/timeout budget (120 s on the harness) and then answered 400 with the
 * SDK's message. The Checkout create call is now bounded, and an outage is told apart from a
 * refusal so the controller can answer a fast 503.
 *
 * <p>A local socket stands in for Stripe: one that accepts and never answers (a hang), and one
 * that answers 500 or 400. No real Stripe is contacted.
 */
class CheckoutOutageTest {

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<Socket> held = new ArrayList<>();

    @AfterEach
    void close() throws IOException {
        for (Socket s : held) {
            s.close();
        }
        for (ServerSocket s : servers) {
            s.close();
        }
    }

    /** A server that accepts every connection and never writes a byte. */
    private int hangingServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        servers.add(server);
        Thread t = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    held.add(server.accept());
                }
            } catch (IOException ignored) {
                // closed by the test
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    /** A server answering every request with one fixed status and a Stripe-shaped error. */
    private int answeringServer(int status) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        servers.add(server);
        Thread t = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.getInputStream().read(new byte[8192]);
                        byte[] body = ("{\"error\":{\"type\":\"" + (status >= 500 ? "api_error" : "invalid_request_error")
                                + "\",\"message\":\"synthetic " + status + "\"}}").getBytes(StandardCharsets.UTF_8);
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // closed by the test
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    private static StripeSdkCheckoutClient client(int port) {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setEnabled(true);
        properties.setSecretKey("sk_test_synthetic");
        properties.setSuccessUrl("http://localhost/success");
        properties.setCancelUrl("http://localhost/cancel");
        properties.setApiBase("http://127.0.0.1:" + port);
        properties.setCheckoutConnectTimeoutMs(500);
        properties.setCheckoutReadTimeoutMs(1000);
        properties.setCheckoutMaxNetworkRetries(1);
        return new StripeSdkCheckoutClient(properties);
    }

    private static StripeCheckoutRequest purchase() {
        return StripeCheckoutRequest.builder()
                .quoteId("q-1").amount(500).currency("gbp").description("Voucher")
                .idempotencyKey("checkout:q-1").connectedAccountId("acct_1").build();
    }

    @Test
    void aHungStripeFailsWithinTheBoundAsUnavailable() throws IOException {
        // S9: before, the buyer waited 120 s. Two attempts of a 1 s read timeout plus backoff
        // must come back in seconds, and say "Stripe is unavailable", not "your request was bad".
        StripeSdkCheckoutClient client = client(hangingServer());

        long started = System.nanoTime();
        StripeCheckoutCreationException e = catchThrowableOfType(
                () -> client.createCheckoutSession(purchase()), StripeCheckoutCreationException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(e).isNotNull();
        assertThat(e.isStripeUnavailable()).isTrue();
        assertThat(took).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void aStripe5xxIsUnavailable() throws IOException {
        // Stripe's own fault: the buyer should retry later, so it is an outage.
        StripeCheckoutCreationException e = catchThrowableOfType(
                () -> client(answeringServer(500)).createCheckoutSession(purchase()),
                StripeCheckoutCreationException.class);

        assertThat(e.isStripeUnavailable()).isTrue();
    }

    @Test
    void aStripe400IsARefusalNotAnOutage() throws IOException {
        // A validation refusal stays a 400 at the controller: retrying cannot help.
        StripeCheckoutCreationException e = catchThrowableOfType(
                () -> client(answeringServer(400)).createCheckoutSession(purchase()),
                StripeCheckoutCreationException.class);

        assertThat(e.isStripeUnavailable()).isFalse();
    }
}
