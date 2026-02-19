package xyz.tcheeric.payment.adapter.test.e2e.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.ratelimit.CashRateLimiter;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import static org.assertj.core.api.Assertions.assertThat;

class CashRateLimitE2ETest extends BaseE2ETest {

    @Autowired
    private CashRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        // Set a low rate limit for testing and reset the bucket state
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 3);
        // Clear internal buckets to reset rate limiter state
        var bucketsField = ReflectionTestUtils.getField(rateLimiter, "buckets");
        if (bucketsField instanceof java.util.Map<?, ?> map) {
            map.clear();
        }
    }

    // Verifies that exceeding rate limit returns 429
    @Test
    void createInvoice_exceedsRateLimit_returns429() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .build();

        // Use up the limit (3 allowed)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<CashInvoiceResponse> resp =
                    restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // 4th request should be rate-limited
        ResponseEntity<Void> limited =
                restTemplate.postForEntity("/cash/invoice", request, Void.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Restore high limit for other tests
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 1000);
    }

    // Verifies that requests within limit all succeed
    @Test
    void createInvoice_withinLimit_allSucceed() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .build();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<CashInvoiceResponse> response =
                    restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // Restore high limit for other tests
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 1000);
    }

    // Verifies that non-create endpoints are not rate-limited
    @Test
    void getInvoice_notRateLimited_afterExceedingCreateLimit() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .build();

        // Create one invoice first
        String ref = restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();

        // Exhaust remaining rate limit
        for (int i = 0; i < 2; i++) {
            restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);
        }

        // Verify POST is rate-limited
        ResponseEntity<Void> limited =
                restTemplate.postForEntity("/cash/invoice", request, Void.class);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // GET should still work
        ResponseEntity<CashInvoiceResponse> getResponse =
                restTemplate.getForEntity("/cash/invoice/" + ref, CashInvoiceResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Restore high limit for other tests
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 1000);
    }
}
