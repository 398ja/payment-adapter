package xyz.tcheeric.payment.adapter.paypal.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import lombok.Builder;
import lombok.Value;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;

/**
 * One PayPal webhook delivery, parsed: {@code StripeWebhookPayload}'s counterpart.
 *
 * <p>The raw bytes are kept as received, because PayPal signs their CRC32. The headers are kept
 * case-insensitively, because the signature check runs after parsing and the servlet request is
 * not the payload's to hold on to.
 */
@Value
@Builder
public class PaypalWebhookPayload implements WebhookPayload {

    String eventId;
    String eventType;
    Instant eventTimestamp;
    byte[] rawBody;
    /** The event's {@code resource}: an order, a capture, a refund or a dispute. */
    JsonNode resource;
    /** The signature headers, case-insensitive. */
    Map<String, String> headers;

    @Override
    public String getIdempotencyKey() {
        return eventId;
    }

    public String header(String name) {
        return headers == null ? null : headers.get(name);
    }

    public static Map<String, String> caseInsensitive(Map<String, String> source) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.putAll(source);
        return out;
    }
}
