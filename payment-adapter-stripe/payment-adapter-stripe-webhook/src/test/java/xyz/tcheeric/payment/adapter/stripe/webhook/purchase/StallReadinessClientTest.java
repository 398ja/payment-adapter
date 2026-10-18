package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient.Readiness;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient.State;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Asking purchase-issuer whether a stall can be issued for (payment-adapter#293), over real HTTP.
 */
class StallReadinessClientTest {

    private static final String STALL = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"ready\":true}";
    private volatile long delayMs = 0;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private StallReadinessClient client() {
        return new StallReadinessClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                Duration.ofMillis(300), Duration.ofSeconds(30), clock);
    }

    // A ready stall reads READY, asked at PI's exact public path.
    @Test
    void aReadyStallIsReady() {
        Readiness r = client().check(STALL);

        assertEquals(State.READY, r.state());
        assertEquals("/api/v1/card-sales/ready/" + STALL, lastPath.get());
    }

    // Not ready carries PI's reason code through untouched, so the buyer's error names it.
    @Test
    void aNotReadyStallCarriesItsReasonCode() {
        body = "{\"ready\":false,\"reason\":\"validity_never_expires\"}";

        Readiness r = client().check(STALL);

        assertEquals(State.NOT_READY, r.state());
        assertEquals("validity_never_expires", r.reason());
    }

    // Not enrolled (and an expired credential) carry no code on the wire, so PA names it generically.
    @Test
    void notReadyWithoutACodeIsNotEnrolled() {
        body = "{\"ready\":false}";

        Readiness r = client().check(STALL);

        assertEquals(State.NOT_READY, r.state());
        assertEquals(StallReadinessClient.NOT_ENROLLED, r.reason());
    }

    // A slow PI is UNKNOWN once the timeout passes, never READY.
    @Test
    void aTimeoutIsUnknown() {
        delayMs = 1500;

        assertEquals(State.UNKNOWN, client().check(STALL).state());
    }

    // A PI error status is UNKNOWN.
    @Test
    void aServerErrorIsUnknown() {
        status = 500;
        body = "{\"error\":\"boom\"}";

        assertEquals(State.UNKNOWN, client().check(STALL).state());
    }

    // A 200 that is not the expected shape is UNKNOWN, not READY.
    @Test
    void aMalformedAnswerIsUnknown() {
        body = "{\"enrolled\":true}";
        assertEquals(State.UNKNOWN, client().check(STALL).state());
        body = "not json";
        assertEquals(State.UNKNOWN, client().check(OTHER).state());
    }

    // PI down entirely (nothing listening) is UNKNOWN.
    @Test
    void anUnreachableIssuerIsUnknown() {
        int port = server.getAddress().getPort();
        server.stop(0);
        StallReadinessClient c = new StallReadinessClient("http://127.0.0.1:" + port,
                Duration.ofMillis(300), Duration.ofSeconds(30), clock);

        assertEquals(State.UNKNOWN, c.check(STALL).state());
    }

    // No base URL configured: fail closed without any call.
    @Test
    void anUnconfiguredClientIsUnknown() {
        StallReadinessClient c = new StallReadinessClient("", Duration.ofMillis(300), Duration.ofSeconds(30), clock);

        Readiness r = c.check(STALL);

        assertEquals(State.UNKNOWN, r.state());
        assertNull(r.reason());
        assertEquals(0, calls.get());
    }

    // A known answer is reused within the TTL, so a burst of checkouts asks PI once.
    @Test
    void aKnownAnswerIsCachedWithinTheTtl() {
        StallReadinessClient c = client();
        c.check(STALL);
        clock.advance(Duration.ofSeconds(29));
        c.check(STALL);

        assertEquals(1, calls.get());
    }

    // After the TTL PI is asked again: a stall that fixed its term goes on sale within a minute.
    @Test
    void theCacheExpiresAfterTheTtl() {
        StallReadinessClient c = client();
        c.check(STALL);
        body = "{\"ready\":false,\"reason\":\"validity_unknown\"}";
        clock.advance(Duration.ofSeconds(31));

        assertEquals(State.NOT_READY, c.check(STALL).state());
        assertEquals(2, calls.get());
    }

    // The cache is per stall: one stall's answer never stands in for another's.
    @Test
    void theCacheIsKeyedPerStall() {
        StallReadinessClient c = client();
        c.check(STALL);
        body = "{\"ready\":false,\"reason\":\"validity_never_expires\"}";

        assertEquals(State.NOT_READY, c.check(OTHER).state());
        assertEquals(2, calls.get());
    }

    // UNKNOWN is never cached: the next checkout asks again rather than refusing for 30 s.
    @Test
    void unknownIsNotCached() {
        StallReadinessClient c = client();
        status = 500;
        c.check(STALL);
        status = 200;

        assertEquals(State.READY, c.check(STALL).state());
        assertEquals(2, calls.get());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
