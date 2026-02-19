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
 * HTTP-based implementation of GatewayWebhookForwarder.
 *
 * <p>Sends payment confirmations to the gateway-app webhook endpoint for
 * post-payment processing (e.g., cash payment voucher minting saga).
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code gateway.webhook.enabled} - enable/disable forwarding (default: true)</li>
 *   <li>{@code gateway.webhook.url} - gateway webhook endpoint URL</li>
 *   <li>{@code gateway.webhook.secret} - HMAC secret for signing</li>
 * </ul>
 */
@Slf4j
@Service
public class HttpGatewayWebhookForwarder implements GatewayWebhookForwarder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${gateway.webhook.enabled:true}")
    private boolean enabled;

    @Value("${gateway.webhook.url:http://localhost:8081/internal/webhook/payment}")
    private String gatewayWebhookUrl;

    @Value("${gateway.webhook.secret:}")
    private String webhookSecret;

    @Value("${gateway.webhook.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${gateway.webhook.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${gateway.webhook.retry.initial-delay-ms:1000}")
    private int initialDelayMs;

    @Value("${gateway.webhook.retry.multiplier:2.0}")
    private double retryMultiplier;

    private final HttpClient httpClient;

    public HttpGatewayWebhookForwarder() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean notifyPaymentConfirmed(PaymentNotification notification) {
        if (!enabled) {
            log.debug("Gateway webhook forwarding disabled, skipping notification");
            return false;
        }

        if (gatewayWebhookUrl == null || gatewayWebhookUrl.isBlank()) {
            log.warn("Gateway webhook URL not configured");
            return false;
        }

        log.info("Forwarding payment notification to gateway: quoteId={}, method={}",
                notification.getQuoteId(), notification.getPaymentMethod());

        int attempt = 0;
        long delayMs = initialDelayMs;

        for (attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                if (sendNotification(notification)) {
                    log.info("Payment notification forwarded to gateway: quoteId={}",
                            notification.getQuoteId());
                    return true;
                }
            } catch (Exception e) {
                log.warn("Failed to forward to gateway (attempt {}/{}): quoteId={}, error={}",
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

        log.error("Failed to forward payment to gateway after {} attempts: quoteId={}",
                maxRetryAttempts, notification.getQuoteId());
        return false;
    }

    private boolean sendNotification(PaymentNotification notification) throws Exception {
        String payload = MAPPER.writeValueAsString(notification);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(gatewayWebhookUrl))
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", notification.getIdempotencyKey())
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload));

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

        log.warn("Gateway webhook returned non-success status: {}, body: {}",
                statusCode, response.body());
        return false;
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
