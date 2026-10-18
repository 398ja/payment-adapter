package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * May this stall be sold a card purchase right now? Asked of purchase-issuer (payment-adapter#293).
 *
 * <p>purchase-issuer is the one that mints the coupon, so it is the one that knows whether it can:
 * enrolled, credential not expired, a voucher term the delegation can cover. Its public answer is
 * {@code GET /api/v1/card-sales/ready/<stall>} → {@code 200 {"ready": bool, "reason"?: code}}
 * (imani-wallet#302/#315). The code is present only for the {@code validity_*} reasons; a stall that
 * is not enrolled, or whose credential expired, answers {@code {"ready": false}} alone, which this
 * reads as {@link #NOT_ENROLLED}.
 *
 * <p>Three answers, and only one lets a checkout through. Anything that is not a well-formed 200
 * (timeout, connection refused, a 5xx, an unexpected body, no URL configured) is
 * {@link State#UNKNOWN}, which the caller refuses: a buyer who cannot pay now loses a tap, a buyer
 * who pays for a coupon that cannot be issued needs an operator refund.
 *
 * <p>Known answers are cached per stall for a short TTL so a burst of checkouts asks once, and a
 * stall that fixes its term goes on sale within that TTL. UNKNOWN is never cached.
 */
@Slf4j
public class StallReadinessClient {

    /** purchase-issuer's path, appended to the base URL. */
    static final String READY_PATH = "/api/v1/card-sales/ready/";

    /** PA's own code for "not ready" with no code on the wire: not enrolled, or credential expired. */
    public static final String NOT_ENROLLED = "not_enrolled";

    /**
     * Strict on purpose (review of #294): a duplicated {@code ready} key, or a second JSON value after
     * the first, is ambiguous, and an ambiguous answer must not be read as whichever value wins.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public enum State { READY, NOT_READY, UNKNOWN }

    /** @param reason the code when NOT_READY, else null */
    public record Readiness(State state, String reason) {
        public static Readiness ready() {
            return new Readiness(State.READY, null);
        }

        public static Readiness notReady(String reason) {
            return new Readiness(State.NOT_READY, reason);
        }

        public static Readiness unknown() {
            return new Readiness(State.UNKNOWN, null);
        }
    }

    private record Cached(Readiness readiness, Instant until) {
    }

    private final String baseUrl;
    private final Duration timeout;
    private final Duration ttl;
    private final Clock clock;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public StallReadinessClient(String baseUrl, Duration timeout, Duration ttl, Clock clock) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        this.timeout = timeout;
        this.ttl = ttl;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /** @param stall a lower-case 64-hex pubkey, already validated by the caller */
    public Readiness check(String stall) {
        Instant now = clock.instant();
        Cached hit = cache.get(stall);
        if (hit != null && now.isBefore(hit.until())) {
            return hit.readiness();
        }
        Readiness fresh = ask(stall);
        if (fresh.state() != State.UNKNOWN) {
            cache.put(stall, new Cached(fresh, now.plus(ttl)));
        } else {
            cache.remove(stall);
        }
        return fresh;
    }

    private Readiness ask(String stall) {
        if (!isConfigured()) {
            log.warn("purchase.issuer.base-url is not set: card checkout refused (fail closed)");
            return Readiness.unknown();
        }
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = null;
        try {
            // The deadline covers the WHOLE answer (review of #294). HttpRequest.timeout stops at the
            // response headers, so a PI that sent 200 and then stalled mid-body held this checkout
            // thread with no bound at all.
            pending = http.sendAsync(
                    HttpRequest.newBuilder(URI.create(baseUrl + READY_PATH + stall))
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                log.warn("purchase-issuer readiness for stall {} answered HTTP {}", stall, response.statusCode());
                return Readiness.unknown();
            }
            return parse(stall, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Readiness.unknown();
        } catch (Exception e) {
            log.warn("purchase-issuer readiness for stall {} unknown: {}", stall, e.toString());
            return Readiness.unknown();
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static Readiness parse(String stall, String body) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("purchase-issuer readiness for stall {} was not JSON", stall);
            return Readiness.unknown();
        }
        JsonNode ready = node == null ? null : node.get("ready");
        if (ready == null || !ready.isBoolean()) {
            log.warn("purchase-issuer readiness for stall {} had no boolean 'ready'", stall);
            return Readiness.unknown();
        }
        if (ready.booleanValue()) {
            return Readiness.ready();
        }
        JsonNode reason = node.get("reason");
        String code = reason != null && reason.isTextual() && reason.textValue().matches("^[a-z_]{1,64}$")
                ? reason.textValue() : NOT_ENROLLED;
        return Readiness.notReady(code);
    }
}
