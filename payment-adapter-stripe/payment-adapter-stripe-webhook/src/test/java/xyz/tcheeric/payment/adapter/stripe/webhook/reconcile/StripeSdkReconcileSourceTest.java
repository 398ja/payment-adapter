package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.CompletedSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.SessionPage;

/**
 * {@link StripeSdkReconcileSource} against stripe-mock's own responses (#272). The fixtures under
 * {@code stripe-mock/} were captured from {@code stripe/stripe-mock:latest} and edited only in the
 * fields a scenario needs (status, payment_status, amounts, refund), so the SDK parses exactly the
 * shapes Stripe serves. Served from loopback, through the same api-base override a developer uses.
 */
class StripeSdkReconcileSourceTest {

    private static final String ACCOUNT = "acct_stall1";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> accountHeaders = new CopyOnWriteArrayList<>();
    private StripeSdkReconcileSource source;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/checkout/sessions", exchange -> serve(exchange, "checkout-sessions-complete.json"));
        server.createContext("/v1/payment_intents/", exchange -> serve(exchange, "payment-intent-refunded.json"));
        server.start();
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setSecretKey("sk_test_reconcile");
        properties.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        source = new StripeSdkReconcileSource(properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(com.sun.net.httpserver.HttpExchange exchange, String fixture) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        accountHeaders.add(exchange.getRequestHeaders().getFirst("Stripe-Account"));
        byte[] body;
        if (status == 200) {
            try (InputStream in = getClass().getResourceAsStream("/stripe-mock/" + fixture)) {
                body = in.readAllBytes();
            }
        } else {
            body = "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Lists complete sessions ON the connected account, in the window, after the cursor's position. */
    @Test
    void listsCompleteSessionsOfTheConnectedAccountInTheWindow() {
        Instant from = Instant.ofEpochSecond(1_791_000_000L);
        Instant until = Instant.ofEpochSecond(1_791_900_100L);

        SessionPage page = source.completeSessions(ACCOUNT, from, until, "cs_test_prev", 50);

        assertThat(accountHeaders).containsExactly(ACCOUNT);
        assertThat(requests.get(0))
                .contains("status=complete")
                .contains("limit=50")
                .contains("starting_after=cs_test_prev")
                .contains("created[gte]=1791000000")
                .contains("created[lte]=1791900100");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.lastId()).isEqualTo("cs_test_unpaid");
        CompletedSession lost = page.sessions().get(0);
        assertThat(lost.paid()).isTrue();
        assertThat(lost.paymentIntentId()).isEqualTo("pi_lost");
        assertThat(lost.amountTotal()).isEqualTo(500L);
        assertThat(lost.currency()).isEqualTo("gbp");
        assertThat(lost.metadata()).containsEntry("buyer_pubkey", "a".repeat(64));
        assertThat(lost.created()).isEqualTo(Instant.ofEpochSecond(1_791_900_000L));
        assertThat(page.sessions().get(1).paid()).isFalse();
    }

    /** The live check reads the PaymentIntent's latest charge, on the account: a refund is seen. */
    @Test
    void readsTheLiveRefundFromTheLatestCharge() {
        PaymentState state = source.paymentState(ACCOUNT, "pi_lost");

        assertThat(accountHeaders).containsExactly(ACCOUNT);
        assertThat(requests.get(0)).startsWith("/v1/payment_intents/pi_lost").contains("latest_charge");
        assertThat(state.refunded()).isTrue();
        assertThat(state.refundedMinor()).isEqualTo(500L);
        assertThat(state.intact()).isFalse();
    }

    /** A 429 is reported as rate limited, so the run stops instead of hammering Stripe. */
    @Test
    void aRateLimitIsReportedAsSuch() {
        status = 429;

        assertThatThrownBy(() -> source.paymentState(ACCOUNT, "pi_lost"))
                .isInstanceOfSatisfying(StripeReconcileException.class,
                        e -> assertThat(e.isRateLimited()).isTrue());
    }
}
