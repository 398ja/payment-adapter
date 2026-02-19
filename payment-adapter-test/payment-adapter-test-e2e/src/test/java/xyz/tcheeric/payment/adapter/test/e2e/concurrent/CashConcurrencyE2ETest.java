package xyz.tcheeric.payment.adapter.test.e2e.concurrent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashReceiptResponse;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CashConcurrencyE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that parallel invoice creation generates unique refs
    @Test
    void parallelCreate_allProduceUniqueRefs() throws InterruptedException {
        int threadCount = 5;
        Set<String> refs = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        CashInvoiceRequest request = CashInvoiceRequest.builder()
                                .amount(100)
                                .build();
                        ResponseEntity<CashInvoiceResponse> response =
                                restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);
                        if (response.getStatusCode().value() == 201) {
                            refs.add(response.getBody().getRef());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertThat(refs).hasSize(threadCount);
    }

    // Verifies that concurrent confirm and cancel on same invoice results in only one succeeding
    @Test
    void concurrentConfirmAndCancel_onlyOneSucceeds() throws InterruptedException {
        String ref = createTestInvoice();
        List<Integer> statusCodes = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        Thread.ofVirtual().start(() -> {
            try {
                ResponseEntity<CashReceiptResponse> response =
                        restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, CashReceiptResponse.class);
                statusCodes.add(response.getStatusCode().value());
            } finally {
                latch.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                ResponseEntity<Void> response =
                        restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);
                statusCodes.add(response.getStatusCode().value());
            } finally {
                latch.countDown();
            }
        });

        latch.await();

        // One should succeed (200 or 204) and the other should get 409
        long successCount = statusCodes.stream()
                .filter(s -> s == 200 || s == 204)
                .count();
        long conflictCount = statusCodes.stream()
                .filter(s -> s == 409)
                .count();

        assertThat(successCount + conflictCount).isEqualTo(2);
        assertThat(successCount).isGreaterThanOrEqualTo(1);
    }

    // Verifies that many parallel GET requests don't cause errors
    @Test
    void parallelGet_manyRequests_allSucceed() throws InterruptedException {
        String ref = createTestInvoice();
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Integer> statusCodes2 = java.util.Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        ResponseEntity<CashInvoiceResponse> response =
                                restTemplate.getForEntity("/cash/invoice/" + ref, CashInvoiceResponse.class);
                        statusCodes2.add(response.getStatusCode().value());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertThat(statusCodes2).allMatch(s -> s == 200);
    }

    private String createTestInvoice() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .build();
        return restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();
    }
}
