package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a guest's return link (imani-wallet#410 G15 §4.2) through imani-mailer's
 * {@code POST /v1/send}, template {@code gift-return-link}. The link contains the refund secret, so
 * it is never logged: failures name the HTTP status only.
 */
public interface ReturnLinkMailer {

    String TEMPLATE = "gift-return-link";

    /** @return true once imani-mailer accepted the message (202), false when refused or unreachable */
    boolean send(String to, String idempotencyKey, Map<String, String> fields);

    /** imani-mailer over HTTP, Bearer-authenticated. */
    @Slf4j
    final class ImaniMailer implements ReturnLinkMailer {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String baseUrl;
        private final String token;
        private final HttpClient http;
        private final Duration timeout;

        public ImaniMailer(String baseUrl, String token, Duration timeout) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.token = token;
            this.timeout = timeout;
            this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public boolean send(String to, String idempotencyKey, Map<String, String> fields) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("template", TEMPLATE);
                body.put("to", to);
                body.put("fields", fields);
                body.put("idempotencyKey", idempotencyKey);
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/send")).timeout(timeout)
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body))).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status != 202) {
                    log.warn("imani-mailer refused a return-link email: HTTP {}", status);
                }
                return status == 202;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("imani-mailer unreachable for a return-link email (interrupted)");
                return false;
            } catch (Exception e) {
                log.warn("imani-mailer unreachable for a return-link email: {}", e.getClass().getSimpleName());
                return false;
            }
        }
    }
}
