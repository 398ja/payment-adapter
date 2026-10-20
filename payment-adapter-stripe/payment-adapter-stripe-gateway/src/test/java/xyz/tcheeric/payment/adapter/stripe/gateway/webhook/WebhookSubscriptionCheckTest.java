package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * payment-adapter#286 item 2: every webhook endpoint must subscribe to every event type its handler
 * acts on. Checked through the real stripe-java reader against a loopback Stripe, so the request
 * ({@code GET /v1/webhook_endpoints/{id}}) and the parsing are Stripe's own.
 */
class WebhookSubscriptionCheckTest {

    private static final String SECRET_KEY = "sk_test_subscription_check_secret";
    private static final String ENDPOINT_ID = "we_payments";
    private static final Set<String> HANDLED = Set.of("checkout.session.completed", "charge.refunded",
            "charge.dispute.created");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String body;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private StripeWebhookEndpointReader reader;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/webhook_endpoints/", this::serve);
        server.start();
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setSecretKey(SECRET_KEY);
        properties.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        reader = new StripeSdkWebhookEndpointReader(properties);
        logs.start();
        ((Logger) LoggerFactory.getLogger(WebhookSubscriptionCheck.class)).addAppender(logs);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((Logger) LoggerFactory.getLogger(WebhookSubscriptionCheck.class)).detachAppender(logs);
    }

    private void serve(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Every handled event subscribed: the gauge reads zero, the check did not fail, no alert. */
    @Test
    void allHandledEventsSubscribedReadsZero() {
        endpointSubscribes("enabled", "checkout.session.completed", "charge.refunded", "charge.dispute.created",
                "checkout.session.expired");

        check(ENDPOINT_ID);

        assertThat(requests).containsExactly("GET /v1/webhook_endpoints/" + ENDPOINT_ID);
        assertThat(missing()).isZero();
        assertThat(failed()).isZero();
        assertThat(alerts()).isEmpty();
    }

    /** One handled event missing: counted, and an [alert] names it so the operator knows what to add. */
    @Test
    void aMissingEventIsCountedAndNamed() {
        endpointSubscribes("enabled", "checkout.session.completed", "charge.refunded");

        check(ENDPOINT_ID);

        assertThat(missing()).isEqualTo(1.0);
        assertThat(failed()).isZero();
        assertThat(alerts()).singleElement().asString().contains("charge.dispute.created", ENDPOINT_ID);
    }

    /** A disabled endpoint delivers nothing, so every handled event is missing. */
    @Test
    void aDisabledEndpointMissesEverything() {
        endpointSubscribes("disabled", "checkout.session.completed", "charge.refunded", "charge.dispute.created");

        check(ENDPOINT_ID);

        assertThat(missing()).isEqualTo(3.0);
    }

    /** {@code *} subscribes to every event, handled ones included. */
    @Test
    void aWildcardSubscriptionMissesNothing() {
        endpointSubscribes("enabled", "*");

        check(ENDPOINT_ID);

        assertThat(missing()).isZero();
    }

    /**
     * API error: the check fails, says so, and never throws (startup must not crash on it). The last
     * known missing count is held rather than reset to a false zero, and the secret key is never logged.
     */
    @Test
    void anApiErrorFailsTheCheckWithoutThrowingOrLeakingTheKey() {
        endpointSubscribes("enabled", "checkout.session.completed");
        WebhookSubscriptionCheck check = checkFor(ENDPOINT_ID);
        check.checkAll();
        status = 500;
        body = "{\"error\":{\"type\":\"api_error\",\"message\":\"Stripe is down\"}}";

        assertThatCode(check::checkAll).doesNotThrowAnyException();

        assertThat(failed()).isEqualTo(1.0);
        assertThat(missing()).isEqualTo(2.0);
        assertThat(alerts()).last().asString().contains("not checked", "Stripe is down");
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(SECRET_KEY));
    }

    /** No endpoint id configured: nothing to ask about, which is itself a failed check, not a pass. */
    @Test
    void anUnconfiguredEndpointFailsTheCheckWithoutCallingStripe() {
        check("");

        assertThat(requests).isEmpty();
        assertThat(failed()).isEqualTo(1.0);
        assertThat(alerts()).singleElement().asString().contains("no endpoint id configured");
    }

    /** Both gauges exist at zero before any check, so an alert sees a healthy zero, not no series. */
    @Test
    void theGaugesAreBoundAtZero() {
        checkFor(ENDPOINT_ID);

        assertThat(missing()).isZero();
        assertThat(failed()).isZero();
    }

    private void endpointSubscribes(String endpointStatus, String... events) {
        StringBuilder list = new StringBuilder();
        for (String event : events) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(event).append('"');
        }
        body = "{\"id\":\"" + ENDPOINT_ID + "\",\"object\":\"webhook_endpoint\",\"status\":\"" + endpointStatus
                + "\",\"enabled_events\":[" + list + "],\"livemode\":false,\"url\":\"https://example.test/webhook/stripe\"}";
    }

    private void check(String endpointId) {
        checkFor(endpointId).checkAll();
    }

    private WebhookSubscriptionCheck checkFor(String endpointId) {
        return new WebhookSubscriptionCheck(List.of(new StripeWebhookEndpoint("payments", endpointId, HANDLED)),
                reader, registry);
    }

    private double missing() {
        return registry.get(WebhookSubscriptionCheck.MISSING).tag("endpoint", "payments").gauge().value();
    }

    private double failed() {
        return registry.get(WebhookSubscriptionCheck.CHECK_FAILED).tag("endpoint", "payments").gauge().value();
    }

    private List<String> alerts() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("[alert]")).toList();
    }
}
