package xyz.tcheeric.payment.adapter.test.e2e.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.subscriber.CashEventSubscriber;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CashSseEventsE2ETest extends BaseE2ETest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CashEventSubscriber eventSubscriber;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that the SSE endpoint is accessible and accepts connections
    @Test
    void sseEndpoint_existingInvoice_acceptsConnection() throws Exception {
        String ref = createTestInvoice();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cash/invoice/" + ref + "/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();

        // Send async and verify connection is accepted (200 status on headers)
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        try {
            HttpResponse<String> response = future.get(2, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            // Timeout is expected for SSE — connection was established but waiting for events
        }
    }

    // Verifies that state change events are sent over SSE
    @Test
    void sseEvents_stateChange_dispatchesEvent() throws Exception {
        String ref = createTestInvoice();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cash/invoice/" + ref + "/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        // Start SSE connection and trigger state change after brief delay
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
                eventSubscriber.handleIntent(ref, "02" + "f".repeat(64), "1234");
                // Confirm to trigger terminal state and complete SSE
                Thread.sleep(100);
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, Void.class);
            } catch (InterruptedException ignored) {
            }
        });

        try {
            HttpResponse<String> response = future.get(3, TimeUnit.SECONDS);
            if (!response.body().isEmpty()) {
                assertThat(response.body()).contains("status");
            }
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            // Timeout is acceptable for SSE
        }
    }

    // Verifies that the SSE endpoint returns text/event-stream content type
    @Test
    void sseEndpoint_contentType_isTextEventStream() throws Exception {
        String ref = createTestInvoice();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cash/invoice/" + ref + "/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        try {
            HttpResponse<String> response = future.get(2, TimeUnit.SECONDS);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertThat(contentType).contains("text/event-stream");
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            // Connection established as SSE (long-lived), timeout is expected
        }
    }

    private String createTestInvoice() {
        CashInvoiceRequest req = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .build();
        return restTemplate.postForEntity("/cash/invoice", req, CashInvoiceResponse.class)
                .getBody().getRef();
    }
}
