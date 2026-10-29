package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import nostr.crypto.schnorr.Schnorr;

/**
 * Is this signed-in buyer a stall? (imani-wallet#410, owner 2026-10-28: the gifting site is for
 * customers and guests only; a stall does not buy gifts there.)
 *
 * <p>A stall is a key with a live, SIGNED kind-30078 {@code d=imani:merchant} record: the same rule
 * as the wallet's {@code merchant.ts}, gateway-core's {@code MerchantAclService} and the
 * subscription service's {@code merchantRole.ts}. Read from gateway-customer's public
 * {@code POST /api/v1/nostr/query}; only events the key itself signed count, the newest wins, and
 * only an explicit {@code "active": false} retires one.
 *
 * <p>Three answers. {@code UNKNOWN} (the gateway could not be asked) refuses the draft too: a role
 * that cannot be checked is not assumed to be the permitted one.
 */
@Slf4j
public class BuyerRoleReader {

    public enum Standing { MERCHANT, CUSTOMER, UNKNOWN }

    /** The read, behind an interface so tests never touch the network. */
    public interface Reader {
        Standing standingOf(String pubkey);
    }

    static final String QUERY_PATH = "/api/v1/nostr/query";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String gatewayBaseUrl;
    private final HttpClient http;
    private final Duration timeout;

    public BuyerRoleReader(String gatewayBaseUrl, Duration timeout) {
        this.gatewayBaseUrl = gatewayBaseUrl == null ? "" : gatewayBaseUrl.trim().replaceAll("/+$", "");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Reader asReader() {
        return this::standingOf;
    }

    public Standing standingOf(String pubkey) {
        if (gatewayBaseUrl.isEmpty()) {
            log.warn("purchase.gifting.gateway-base-url is not set: signed-in gift drafts are refused (fail closed)");
            return Standing.UNKNOWN;
        }
        String key = pubkey == null ? "" : pubkey.toLowerCase();
        if (!key.matches("^[0-9a-f]{64}$")) {
            return Standing.UNKNOWN;
        }
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "kinds", new int[] {30078},
                    "authors", new String[] {key},
                    "tags", Map.of("#d", new String[] {"imani:merchant"}),
                    "limit", 50));
            HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBaseUrl + QUERY_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body().length() > 512 * 1024) {
                return Standing.UNKNOWN;
            }
            JsonNode events = MAPPER.readTree(res.body()).path("events");
            if (!events.isArray()) {
                return Standing.UNKNOWN;
            }
            return standingFrom(events, key);
        } catch (Exception e) {
            return Standing.UNKNOWN;
        }
    }

    /**
     * The stall's display name, from its own newest SIGNED kind-0 profile ({@code name}, else
     * {@code display_name}), or empty. Best effort, for an email's wording only: never a key, never
     * a handle, and nothing is decided by it.
     */
    public java.util.Optional<String> displayNameOf(String pubkey) {
        String key = pubkey == null ? "" : pubkey.toLowerCase();
        if (gatewayBaseUrl.isEmpty() || !key.matches("^[0-9a-f]{64}$")) {
            return java.util.Optional.empty();
        }
        try {
            String body = MAPPER.writeValueAsString(Map.of("kinds", new int[] {0}, "authors", new String[] {key}, "limit", 10));
            HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBaseUrl + QUERY_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body().length() > 512 * 1024) {
                return java.util.Optional.empty();
            }
            return displayNameFrom(MAPPER.readTree(res.body()).path("events"), key);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** Pure: the name in the newest kind-0 this key signed. */
    static java.util.Optional<String> displayNameFrom(JsonNode events, String key) {
        JsonNode newest = null;
        long newestAt = Long.MIN_VALUE;
        for (JsonNode e : events) {
            if (e.path("kind").asInt() != 0 || !signedBy(e, key, 0)) {
                continue;
            }
            long at = createdAt(e);
            if (at > newestAt) {
                newest = e;
                newestAt = at;
            }
        }
        if (newest == null) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode c = MAPPER.readTree(newest.path("content").asText(""));
            String name = c.path("name").asText("").trim();
            if (name.isEmpty()) {
                name = c.path("display_name").asText("").trim();
            }
            // One line, no markup: it goes into an email field typed `line`.
            name = name.replaceAll("[\\p{Cntrl}]", " ").trim();
            return name.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(name.length() > 120 ? name.substring(0, 120) : name);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** NIP-01: the id is the canonical hash and the signature is the author's. */
    private static boolean signedBy(JsonNode e, String key, int kind) {
        if (!key.equalsIgnoreCase(e.path("pubkey").asText(""))) {
            return false;
        }
        try {
            ArrayNode canonical = MAPPER.createArrayNode();
            canonical.add(0);
            canonical.add(key);
            canonical.add(createdAt(e));
            canonical.add(kind);
            canonical.add(e.path("tags"));
            canonical.add(e.path("content").asText(""));
            byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            if (!HexFormat.of().formatHex(id).equalsIgnoreCase(e.path("id").asText(""))) {
                return false;
            }
            return Schnorr.verify(id, HexFormat.of().parseHex(key), HexFormat.of().parseHex(e.path("sig").asText("")));
        } catch (Exception ex) {
            return false;
        }
    }

    /** Pure: the standing a list of candidate events gives this key. */
    static Standing standingFrom(JsonNode events, String key) {
        JsonNode newest = null;
        long newestAt = Long.MIN_VALUE;
        for (JsonNode e : events) {
            if (!isStallRecord(e, key)) {
                continue;
            }
            long at = createdAt(e);
            if (at > newestAt) {
                newest = e;
                newestAt = at;
            }
        }
        if (newest == null) {
            return Standing.CUSTOMER;
        }
        try {
            JsonNode content = MAPPER.readTree(newest.path("content").asText(""));
            return content.path("active").isBoolean() && !content.path("active").asBoolean() ? Standing.CUSTOMER : Standing.MERCHANT;
        } catch (Exception e) {
            // A record that does not parse still counts: publishing it is what makes a stall.
            return Standing.MERCHANT;
        }
    }

    private static long createdAt(JsonNode e) {
        JsonNode at = e.has("created_at") ? e.path("created_at") : e.path("createdAt");
        return at.asLong(Long.MIN_VALUE);
    }

    private static boolean isStallRecord(JsonNode e, String key) {
        if (e.path("kind").asInt() != 30078 || !key.equalsIgnoreCase(e.path("pubkey").asText(""))) {
            return false;
        }
        int dTags = 0;
        boolean merchantD = false;
        for (JsonNode t : e.path("tags")) {
            if (t.isArray() && t.size() >= 2 && "d".equals(t.get(0).asText())) {
                dTags++;
                merchantD = "imani:merchant".equals(t.get(1).asText());
            }
        }
        if (dTags != 1 || !merchantD) {
            return false;
        }
        try {
            ArrayNode canonical = MAPPER.createArrayNode();
            canonical.add(0);
            canonical.add(key);
            canonical.add(createdAt(e));
            canonical.add(30078);
            canonical.add(e.path("tags"));
            canonical.add(e.path("content").asText(""));
            byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            if (!HexFormat.of().formatHex(id).equalsIgnoreCase(e.path("id").asText(""))) {
                return false;
            }
            return Schnorr.verify(id, HexFormat.of().parseHex(key), HexFormat.of().parseHex(e.path("sig").asText("")));
        } catch (Exception ex) {
            return false;
        }
    }
}
