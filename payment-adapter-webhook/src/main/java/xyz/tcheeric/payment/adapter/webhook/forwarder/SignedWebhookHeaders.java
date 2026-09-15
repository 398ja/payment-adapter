package xyz.tcheeric.payment.adapter.webhook.forwarder;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * The signed-webhook contract shared by every receiver in the estate.
 *
 * <p>Both {@code cashu-mint}'s {@code WebhookSignatureValidator} and {@code gateway-core}'s
 * {@code PaymentWebhookValidator} require the same thing:
 *
 * <ul>
 *   <li>{@code X-Webhook-Timestamp}: epoch seconds, inside the receiver's tolerance window</li>
 *   <li>{@code X-Webhook-Signature}: Base64 HMAC-SHA256 over {@code "<timestamp>.<body>"}</li>
 * </ul>
 *
 * <p>This class exists because the rule was previously implemented twice, once per forwarder, and
 * the two copies disagreed. When the receivers began requiring the timestamp, one forwarder was
 * fixed and the other was missed -- the second was found only by auditing for it. Duplication of a
 * protocol rule across senders is exactly how half of them end up wrong, so there is now one
 * implementation and the forwarders differ only in where they post.
 *
 * <p>The timestamp is part of the signed material rather than a bare header. As a header alone it
 * could be edited and the request replayed, which is what the receivers' windows exist to prevent.
 */
@Slf4j
final class SignedWebhookHeaders {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";

    /** The separator the receivers split on; it cannot occur in an epoch-seconds value. */
    private static final String SIGNED_PAYLOAD_SEPARATOR = ".";

    private SignedWebhookHeaders() {
    }

    /**
     * Adds the signature and timestamp headers, when a secret is configured.
     *
     * <p>An absent or blank secret means the deployment has not enabled signing, so the request
     * goes out unsigned and the receiver decides whether to accept it. That is the pre-existing
     * behaviour of both forwarders and is left unchanged.
     *
     * @param request the builder to add headers to
     * @param payload the exact body that will be sent; the MAC must cover these bytes
     * @param secret  the shared secret, or null/blank when signing is disabled
     * @param clock   source of the timestamp, injectable so a test can pin it
     */
    static void apply(HttpRequest.Builder request, String payload, String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        request.header(SIGNATURE_HEADER, sign(timestamp, payload, secret));
        request.header(TIMESTAMP_HEADER, timestamp);
    }

    /** The Base64 HMAC-SHA256 both receivers recompute and compare against. */
    private static String sign(String timestamp, String payload, String secret) {
        String signedPayload = timestamp + SIGNED_PAYLOAD_SEPARATOR + payload;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Failed to compute webhook signature", e);
            throw new IllegalStateException("Failed to compute signature", e);
        }
    }
}
