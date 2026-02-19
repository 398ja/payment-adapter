package xyz.tcheeric.payment.adapter.webhook.forwarder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-based implementation of MintWebhookForwarder.
 *
 * <p>Sends payment notifications to the cashu-mint webhook endpoint with:
 * <ul>
 *   <li>HMAC signature for authentication</li>
 *   <li>Retry logic with exponential backoff</li>
 *   <li>Configurable timeout</li>
 * </ul>
 */
@Slf4j
@Service
public class HttpMintWebhookForwarder implements MintWebhookForwarder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${mint.webhook.enabled:true}")
    private boolean enabled;

    @Value("${mint.webhook.url:http://localhost:7777/webhook/payment}")
    private String mintWebhookUrl;

    @Value("${mint.webhook.secret:}")
    private String webhookSecret;

    @Value("${mint.webhook.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${mint.webhook.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${mint.webhook.retry.initial-delay-ms:1000}")
    private int initialDelayMs;

    @Value("${mint.webhook.retry.multiplier:2.0}")
    private double retryMultiplier;

    private final HttpClient httpClient;

    public HttpMintWebhookForwarder() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean notifyPaymentReceived(PaymentNotification notification) {
        if (!enabled) {
            log.debug("Mint webhook forwarding disabled, skipping notification");
            return false;
        }

        if (mintWebhookUrl == null || mintWebhookUrl.isBlank()) {
            log.warn("Mint webhook URL not configured");
            return false;
        }

        log.info("Forwarding payment notification to mint: quoteId={}, method={}",
                notification.getQuoteId(), notification.getPaymentMethod());

        int attempt = 0;
        long delayMs = initialDelayMs;

        for (attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                if (sendNotification(notification)) {
                    log.info("Payment notification forwarded successfully: quoteId={}",
                            notification.getQuoteId());
                    return true;
                }
            } catch (Exception e) {
                log.warn("Failed to forward payment notification (attempt {}/{}): quoteId={}, error={}",
                        attempt, maxRetryAttempts, notification.getQuoteId(), e.getMessage());
            }

            if (attempt < maxRetryAttempts) {
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                    delayMs = (long) (delayMs * retryMultiplier);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.error("Failed to forward payment notification after {} attempts: quoteId={}",
                maxRetryAttempts, notification.getQuoteId());
        return false;
    }

    private boolean sendNotification(PaymentNotification notification) throws Exception {
        String payload = serializePayload(notification);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mintWebhookUrl))
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", notification.getIdempotencyKey())
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload));

        // Add signature if secret is configured
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            String signature = computeSignature(payload);
            requestBuilder.header("X-Webhook-Signature", signature);
        }

        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return true;
        }

        log.warn("Mint webhook returned non-success status: {}, body: {}",
                statusCode, response.body());
        return false;
    }

    private String serializePayload(PaymentNotification notification) throws JsonProcessingException {
        return MAPPER.writeValueAsString(notification);
    }

    private String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to compute webhook signature", e);
            throw new RuntimeException("Failed to compute signature", e);
        }
    }
}
