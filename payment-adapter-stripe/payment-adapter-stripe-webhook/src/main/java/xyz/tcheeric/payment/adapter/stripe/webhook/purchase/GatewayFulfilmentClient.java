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
            String issuerId = body.hasNonNull("issuerId") ? body.get("issuerId").asText() : null;
            return body.path("fulfilled").asBoolean(false)
                    ? new Answer(Fulfilment.FULFILLED, issuerId)
                    : new Answer(Fulfilment.NOT_FULFILLED, issuerId);
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
     * @param issuerId   who the gateway says issued, or null when it did not say
     */
    public record Answer(Fulfilment fulfilment, String issuerId) {
    }

    /**
     * A NIP-98 header for one GET.
     *
     * <p>No payload tag: the spec only requires one for requests with a body,
     * and a GET has none.
     */
    private String authorization(String method, String url) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String pubkey = identity.getPublicKey().toString();

        // Built by hand rather than through the event builders: the id is a
        // hash over a canonical array, and doing it here keeps the wire format
        // visible at the point it matters.
        String tags = "[[\"u\",\"" + url + "\"],[\"method\",\"" + method + "\"]]";
        String serialised = "[0,\"" + pubkey + "\"," + now + ",27235," + tags + ",\"\"]";
        byte[] id = MessageDigest.getInstance("SHA-256")
                .digest(serialised.getBytes(StandardCharsets.UTF_8));
        String idHex = HexFormat.of().formatHex(id);

        String signature = identity.sign(new EventId(id)).toString();

        String event = "{\"id\":\"" + idHex + "\",\"pubkey\":\"" + pubkey + "\",\"created_at\":" + now
                + ",\"kind\":27235,\"tags\":" + tags + ",\"content\":\"\",\"sig\":\"" + signature + "\"}";

        return "Nostr " + Base64.getEncoder()
                .encodeToString(event.getBytes(StandardCharsets.UTF_8));
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
