package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.common.QuoteRef;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Re-posts phoenixd's own payment callback to the adapter's webhook endpoint.
 *
 * <p>Used when the gateway learns from phoenixd directly that an invoice was paid but the
 * callback never reached the adapter. Replaying the callback, rather than talking to the mint
 * from here, keeps one path for "tell the mint": the adapter's webhook handler validates the
 * amount, forwards with the HMAC signature the mint requires, and stamps {@code mintNotifiedAt}.
 * A replay that fails leaves the quote PAID with no stamp, which is exactly what
 * {@code PaidQuoteForwardReconciler} and the {@code payment_adapter_paid_unforwarded} gauge see.
 */
@Slf4j
public class AdapterWebhookRelay {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * Posts {@code type=payment_received} for the quote, in phoenixd's form-encoded shape.
     *
     * @return {@code true} when the adapter answered 2xx
     */
    public boolean relayPaymentReceived(URL webhookUrl, String quoteId, Integer amountSat, String paymentHash) {
        String form = "type=payment_received"
                + "&amountSat=" + (amountSat == null ? "" : amountSat)
                + "&paymentHash=" + encode(paymentHash)
                + "&externalId=" + encode(quoteId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.toString()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            log.warn("phoenixd_gateway webhook_relay_refused quote_ref={} status={}", QuoteRef.of(quoteId), status);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("phoenixd_gateway webhook_relay_interrupted quote_ref={}", QuoteRef.of(quoteId));
            return false;
        } catch (Exception e) {
            log.warn("phoenixd_gateway webhook_relay_failed quote_ref={} error={}",
                    QuoteRef.of(quoteId), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
