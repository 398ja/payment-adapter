package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;

/**
 * Every call to PayPal's REST API goes through here, as every Stripe call goes through
 * {@code StripeGatewayProperties#requestOptions()}: one place for the API base, the platform's
 * credentials, the timeouts and the retries, so no call can drift to another host.
 *
 * <ul>
 *   <li><b>Platform credentials.</b> An OAuth client-credentials token, cached until a minute before
 *       it expires and fetched again once on a 401.</li>
 *   <li><b>Acting as a seller.</b> {@link Call#asSeller(String)} adds {@code PayPal-Auth-Assertion}
 *       naming the seller's merchant id: the counterpart of {@code Stripe-Account}. An unsigned JWT,
 *       as PayPal specifies it: the platform's token is the credential, the assertion only says on
 *       whose behalf.</li>
 *   <li><b>Idempotency.</b> {@link Call#requestId(String)} sets {@code PayPal-Request-Id}, so a retry
 *       of a create or capture is answered with the first result rather than done twice.</li>
 *   <li><b>Retries.</b> Only when PayPal could not answer (no response, 5xx, 429), at most
 *       {@code paypal.max-network-retries} times. A refusal (4xx) is never retried.</li>
 * </ul>
 */
@Slf4j
public class PaypalHttp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TOKEN_EARLY_REFRESH = Duration.ofSeconds(60);

    private final PaypalGatewayProperties properties;
    private final HttpClient http;
    private final Clock clock;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public PaypalHttp(PaypalGatewayProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Clock.systemUTC());
    }

    public PaypalHttp(PaypalGatewayProperties properties, HttpClient http, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.clock = clock;
    }

    public PaypalGatewayProperties properties() {
        return properties;
    }

    public Call get(String path) {
        return new Call("GET", path, null);
    }

    public Call post(String path, Object body) {
        return new Call("POST", path, body);
    }

    /** One API call, built up and then {@link #send() sent}. */
    public final class Call {
        private final String method;
        private final String path;
        private final Object body;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String> query = new LinkedHashMap<>();

        private Call(String method, String path, Object body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        /** Act as this seller ({@code PayPal-Auth-Assertion}). */
        public Call asSeller(String merchantId) {
            if (merchantId != null && !merchantId.isBlank()) {
                headers.put("PayPal-Auth-Assertion", authAssertion(properties.getClientId(), merchantId));
            }
            return this;
        }

        public Call requestId(String requestId) {
            if (requestId != null && !requestId.isBlank()) {
                headers.put("PayPal-Request-Id", requestId);
            }
            return this;
        }

        public Call query(String name, String value) {
            if (value != null) {
                query.put(name, value);
            }
            return this;
        }

        /** @return PayPal's JSON answer, or an empty object for a 204 */
        public JsonNode send() {
            String target = properties.resolvedApiBase() + path + queryString(query);
            String payload = body == null ? null : write(body);
            int attempts = 1 + Math.max(0, properties.getMaxNetworkRetries());
            PaypalApiException last = null;
            boolean refreshedToken = false;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    return once(target, payload);
                } catch (PaypalApiException e) {
                    if (e.status() == 401 && !refreshedToken) {
                        // A token PayPal revoked before its stated expiry: fetch one, once.
                        invalidateToken();
                        refreshedToken = true;
                        attempt--;
                        continue;
                    }
                    if (!e.isUnavailable()) {
                        throw e;
                    }
                    last = e;
                    log.warn("paypal {} {} attempt {}/{} unavailable: {}", method, path, attempt, attempts,
                            e.getMessage());
                }
            }
            throw last;
        }

        private JsonNode once(String target, String payload) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Accept", "application/json")
                    .header("Prefer", "return=representation");
            if (properties.getBnCode() != null && !properties.getBnCode().isBlank()) {
                request.header("PayPal-Partner-Attribution-Id", properties.getBnCode().trim());
            }
            headers.forEach(request::header);
            if (payload != null) {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return exchange(request.build(), method + " " + path);
        }
    }

    /** The unsigned JWT PayPal reads as "the platform, acting for this seller". */
    static String authAssertion(String clientId, String merchantId) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(write(Map.of("iss", clientId, "payer_id", merchantId))
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }

    private String accessToken() {
        Instant now = clock.instant();
        String current = token;
        if (current != null && now.isBefore(tokenExpiresAt)) {
            return current;
        }
        synchronized (this) {
            if (token != null && clock.instant().isBefore(tokenExpiresAt)) {
                return token;
            }
            String basic = Base64.getEncoder().encodeToString(
                    (properties.getClientId() + ":" + properties.getClientSecret()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.resolvedApiBase() + "/v1/oauth2/token"))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Authorization", "Basic " + basic)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();
            JsonNode answer = exchange(request, "POST /v1/oauth2/token");
            String fresh = answer.path("access_token").asText(null);
            if (fresh == null || fresh.isBlank()) {
                throw new PaypalApiException("PayPal returned no access token", 0, null, null, null);
            }
            long ttl = answer.path("expires_in").asLong(300);
            tokenExpiresAt = clock.instant().plusSeconds(ttl).minus(TOKEN_EARLY_REFRESH);
            token = fresh;
            return fresh;
        }
    }

    private synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private JsonNode exchange(HttpRequest request, String what) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PaypalApiException("PayPal " + what + " could not be reached: " + e.getMessage(), 0, null,
                    null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaypalApiException("PayPal " + what + " interrupted", 0, null, null, e);
        }
        int status = response.statusCode();
        JsonNode body = read(response.body());
        if (status >= 200 && status < 300) {
            return body;
        }
        String issue = body.path("details").path(0).path("issue").asText(null);
        if (issue == null) {
            issue = body.path("name").asText(body.path("error").asText(null));
        }
        String debugId = body.path("debug_id").asText(response.headers().firstValue("Paypal-Debug-Id").orElse(null));
        String message = body.path("message").asText(body.path("error_description").asText(""));
        throw new PaypalApiException("PayPal " + what + " answered " + status
                + (issue == null ? "" : " " + issue) + (message.isBlank() ? "" : ": " + message)
                + (debugId == null ? "" : " (debug_id " + debugId + ")"), status, issue, debugId, null);
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.createObjectNode().put("message", body.length() > 200 ? body.substring(0, 200) : body);
        }
    }

    private static String write(Object body) {
        try {
            return body instanceof String s ? s : JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("PayPal request body is not JSON", e);
        }
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("?");
        query.forEach((name, value) -> {
            if (out.length() > 1) {
                out.append('&');
            }
            out.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    /** A path segment from an id PayPal gave us or a caller sent: encoded, never trusted as a path. */
    public static String segment(String id) {
        return URLEncoder.encode(id == null ? "" : id, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
