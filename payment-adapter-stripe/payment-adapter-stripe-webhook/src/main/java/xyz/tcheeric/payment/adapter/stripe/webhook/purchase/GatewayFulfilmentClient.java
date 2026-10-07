package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import nostr.id.Identity;

/**
 * Asks the gateway whether a coupon was really issued.
 *
 * <p>One question, one boolean, and that narrowness is what keeps this from
 * being a coupon dependency. It learns no token, no recipient, no amount —
 * {@code VoucherFulfilmentController} deliberately returns none of those, so a
 * payments service asking this stays a payments service.
 *
 * <h2>Why a payments service asks at all</h2>
 *
 * <p>The alternative is trusting a self-reported discharge, and the whole of
 * {@code imani-woo} ADR 0009 is that fulfilment must be confirmed rather than
 * asserted. Recording an unverified claim would leave a customer who paid for
 * nothing looking, in the database, exactly like one who was served.
 *
 * <h2>The signing key, which is a new kind of secret here</h2>
 *
 * <p>The endpoint sits under {@code /api/v1/atomic}, which requires NIP-98, so
 * this service needs a key of its own. It is worth being precise about what
 * that key can do: it identifies this service and nothing more. It issues no
 * coupons, holds no stall's authority, and its loss lets an attacker ask the
 * gateway about payment request ids they already possess. That is a much
 * smaller secret than the Stripe platform key already living here.
 */
@Slf4j
public class GatewayFulfilmentClient {

    /** Matches the gateway's own floor: shorter ids are not capabilities. */
    private static final int MINIMUM_ID_LENGTH = 16;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** NIP-98's kind for an HTTP authorization event. */
    private static final int KIND_HTTP_AUTH = 27235;

    private final String baseUrl;
    private final Identity identity;
    private final HttpClient http;
    private final Duration timeout;

    public GatewayFulfilmentClient(String baseUrl, String privateKeyHex, Duration timeout) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.identity = privateKeyHex == null || privateKeyHex.isBlank()
                ? null
                : Identity.create(privateKeyHex);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Whether this client is configured enough to answer anything. */
    public boolean isEnabled() {
        return !baseUrl.isBlank() && identity != null;
    }

    /**
     * Was this payment request answered by a completed send?
     *
     * <p>Never throws. Every failure is {@link Fulfilment#UNKNOWN}, because a
     * thrown exception here would have to be interpreted by the caller, and the
     * likely interpretation — "not fulfilled" — is the one ADR 0009 forbids.
     */
    public Answer check(String paymentRequestId) {
        if (!isEnabled()) {
            // Unconfigured is unknown, not false. A deployment that forgot to
            // set this must not silently start discharging debts on trust.
            log.warn("Fulfilment verification is not configured; cannot confirm issuance");
            return new Answer(Fulfilment.UNKNOWN, null);
        }
        if (paymentRequestId == null || paymentRequestId.length() < MINIMUM_ID_LENGTH) {
            return new Answer(Fulfilment.UNKNOWN, null);
        }

        String url = baseUrl + "/api/v1/atomic/fulfilment/" + paymentRequestId;

        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(timeout)
                            .header("Authorization", authorization("GET", url))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // A refusal is not an answer about the voucher. Treating a 401
                // as "not issued" would blame an issuer for our own misconfigured
                // credentials.
                log.warn("Fulfilment check for {} returned status {}",
                        truncate(paymentRequestId), response.statusCode());
                return new Answer(Fulfilment.UNKNOWN, null);
            }

            JsonNode body = MAPPER.readTree(response.body());
            return new Answer(
                    body.path("fulfilled").asBoolean(false) ? Fulfilment.FULFILLED : Fulfilment.NOT_FULFILLED,
                    text(body, "issuerId"),
                    text(body, "recipientPubkey"),
                    body.hasNonNull("amount") && body.get("amount").canConvertToLong()
                            ? body.get("amount").asLong() : null,
                    text(body, "unit"),
                    text(body, "reason"),
                    text(body, "creatorPubkey"));
        } catch (Exception e) {
            // Unreachable means UNKNOWN. ADR 0009: a network problem never
            // discharges a debt and never accuses a merchant of not issuing.
            log.warn("Could not reach the gateway to confirm {}: {}",
                    truncate(paymentRequestId), e.getMessage());
            return new Answer(Fulfilment.UNKNOWN, null);
        }
    }

    /**
     * What the gateway said, in one round trip.
     *
     * <p>The issuer travels with the verdict rather than behind a second call,
     * because checking WHO issued is part of confirming fulfilment: a completed
     * send from some other stall does not discharge this stall's debt, and
     * ADR 0009 makes that check explicit.
     *
     * @param fulfilment the verdict, including the UNKNOWN that means "do not act"
     * @param issuerId      who the gateway says issued, or null when it did not say
     * @param creatorPubkey the key that registered the request's terms (imani-gateway-core#131,
     *                      fb2b76a), or null from a core that does not say. Registration is
     *                      first-writer-wins and open to any NIP-98 key, so the terms are only
     *                      this service's own when this is {@link #publicKeyHex()}.
     */
    public record Answer(Fulfilment fulfilment, String issuerId, String recipientPubkey,
                         Long amount, String unit, String reason, String creatorPubkey) {

        /** The two-fact answer, as a gateway older than imani-gateway-core#131 gives it. */
        public Answer(Fulfilment fulfilment, String issuerId) {
            this(fulfilment, issuerId, null, null, null, null, null);
        }

        /** The answer from a core with registration but before it named the registering key. */
        public Answer(Fulfilment fulfilment, String issuerId, String recipientPubkey,
                      Long amount, String unit, String reason) {
            this(fulfilment, issuerId, recipientPubkey, amount, unit, reason, null);
        }

        /** gateway-core#131's reason for a request nobody registered. */
        public boolean unregistered() {
            return "unregistered".equals(reason);
        }
    }

    /** What happened to a registration. */
    public enum Registration {
        /** 201: the terms are now bound to the request. */
        REGISTERED,
        /** 200: the same terms were already bound. Same as success. */
        ALREADY_REGISTERED,
        /** 409: different terms got there first. This request can never be confirmed for us. */
        CONFLICT,
        /** 404/405: a gateway older than imani-gateway-core#131. Logged; the flow carries on. */
        UNSUPPORTED,
        /** Not attempted or not answered: unconfigured, missing facts, unreachable, other status. */
        FAILED
    }

    /**
     * Bind a payment request to its terms (imani-gateway-core#131).
     *
     * <p>gateway-core counts a send towards a request only when it went to the
     * registered recipient, of the registered issuer and unit, and sends add up
     * to the registered amount. Without this, the GET answers "unregistered" and
     * nothing can be discharged. First writer wins, so calling this as soon as
     * the id exists is what stops anyone else pricing it.
     *
     * <p>Never throws. A gateway from before #131 has no PUT, and that must not
     * stop a purchase being recorded: 404/405 is logged and reported as
     * {@link Registration#UNSUPPORTED}.
     *
     * @param amount minor units of {@code unit}
     */
    public Registration register(String paymentRequestId, String recipientPubkey, String issuerId,
                                 long amount, String unit) {
        if (!isEnabled() || paymentRequestId == null || paymentRequestId.length() < MINIMUM_ID_LENGTH
                || recipientPubkey == null || issuerId == null || unit == null || amount <= 0) {
            return Registration.FAILED;
        }

        String url = baseUrl + "/api/v1/atomic/fulfilment/" + paymentRequestId;

        try {
            com.fasterxml.jackson.databind.node.ObjectNode terms = MAPPER.createObjectNode();
            terms.put("recipientPubkey", recipientPubkey);
            terms.put("issuerId", issuerId);
            terms.put("amount", amount);
            terms.put("unit", unit);
            byte[] body = MAPPER.writeValueAsBytes(terms);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("Authorization", authorization("PUT", url, body))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 201 -> Registration.REGISTERED;
                case 200 -> Registration.ALREADY_REGISTERED;
                case 409 -> {
                    log.warn("Payment request {} was registered with other terms first", truncate(paymentRequestId));
                    yield Registration.CONFLICT;
                }
                case 404, 405 -> {
                    log.warn("Gateway has no fulfilment registration (HTTP {}); {} will need a manual check",
                            response.statusCode(), truncate(paymentRequestId));
                    yield Registration.UNSUPPORTED;
                }
                default -> {
                    log.warn("Registering {} returned status {}", truncate(paymentRequestId), response.statusCode());
                    yield Registration.FAILED;
                }
            };
        } catch (Exception e) {
            log.warn("Could not reach the gateway to register {}: {}", truncate(paymentRequestId), e.getMessage());
            return Registration.FAILED;
        }
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    /**
     * A NIP-98 header for one GET.
     *
     * <p>No payload tag: the spec only requires one for requests with a body,
     * and a GET has none.
     */
    private String authorization(String method, String url) throws Exception {
        return authorization(method, url, null);
    }

    /** With a body, NIP-98 adds a {@code payload} tag: the SHA-256 of the exact bytes sent. */
    private String authorization(String method, String url, byte[] payload) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String pubkey = identity.getPublicKey().toString();

        /*
         * Serialised by Jackson, NOT by string concatenation.
         *
         * The first version built this JSON by hand. A payment request id or a
         * base URL containing a quote or a backslash would have escaped the
         * string it was in, and the consequences run from a malformed event the
         * gateway rejects to a forged extra tag inside a signed event. Neither
         * is acceptable when the whole point of the event is to be trusted.
         *
         * NIP-01's id is a hash over an array with EXACTLY this shape and
         * order: [0, pubkey, created_at, kind, tags, content]. Jackson escapes
         * the values and preserves the order, which is all the canonical form
         * requires here.
         */
        com.fasterxml.jackson.databind.node.ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(url));
        tags.add(MAPPER.createArrayNode().add("method").add(method));
        if (payload != null) {
            tags.add(MAPPER.createArrayNode().add("payload")
                    .add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))));
        }

        com.fasterxml.jackson.databind.node.ArrayNode canonical = MAPPER.createArrayNode();
        canonical.add(0);
        canonical.add(pubkey);
        canonical.add(now);
        canonical.add(KIND_HTTP_AUTH);
        canonical.add(tags);
        canonical.add("");

        byte[] id = MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsBytes(canonical));

        com.fasterxml.jackson.databind.node.ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey);
        event.put("created_at", now);
        event.put("kind", KIND_HTTP_AUTH);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", identity.sign(new EventId(id)).toString());

        return "Nostr " + Base64.getEncoder()
                .encodeToString(MAPPER.writeValueAsBytes(event));
    }

    /**
     * This service's own public key, hex.
     *
     * <p>Exposed so a deployment can enrol it: the gateway must recognise the
     * key before any fulfilment check will be authorised, and discovering that
     * at the first discharge is worse than reading it from a health endpoint.
     */
    public String publicKeyHex() {
        return identity == null ? null : identity.getPublicKey().toString();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12) + "...";
    }

    /**
     * The 32 bytes a NIP-98 event's signature covers.
     *
     * <p>{@code ISignable} is not a functional interface, so this cannot be a
     * lambda. It carries the event id and nothing else, because that is the
     * whole of what BIP-340 signs here.
     */
    private static final class EventId implements nostr.base.ISignable {

        private final byte[] id;
        private nostr.base.Signature signature;

        private EventId(byte[] id) {
            this.id = id;
        }

        @Override
        public nostr.base.Signature getSignature() {
            return signature;
        }

        @Override
        public void setSignature(nostr.base.Signature signature) {
            this.signature = signature;
        }

        @Override
        public java.util.function.Consumer<nostr.base.Signature> getSignatureConsumer() {
            return value -> this.signature = value;
        }

        @Override
        public java.util.function.Supplier<java.nio.ByteBuffer> getByteArraySupplier() {
            return () -> java.nio.ByteBuffer.wrap(id);
        }
    }
}
