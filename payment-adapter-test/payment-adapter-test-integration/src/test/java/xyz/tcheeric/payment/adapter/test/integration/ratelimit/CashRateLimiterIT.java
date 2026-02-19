package xyz.tcheeric.payment.adapter.test.integration.ratelimit;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.cash.gateway.ratelimit.CashRateLimiter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CashRateLimiterIT {

    // Verifies that requests within the limit are allowed
    @Test
    void tryAcquire_withinLimit_returnsTrue() {
        CashRateLimiter limiter = createLimiter(10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("test-source")).isTrue();
        }
    }

    // Verifies that requests exceeding the limit are rejected
    @Test
    void tryAcquire_exceedsLimit_returnsFalse() {
        CashRateLimiter limiter = createLimiter(5);

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("test-source");
        }

        assertThat(limiter.tryAcquire("test-source")).isFalse();
    }

    // Verifies that different sources have independent rate limits
    @Test
    void tryAcquire_differentSources_independentLimits() {
        CashRateLimiter limiter = createLimiter(2);

        assertThat(limiter.tryAcquire("source-a")).isTrue();
        assertThat(limiter.tryAcquire("source-a")).isTrue();
        assertThat(limiter.tryAcquire("source-a")).isFalse();

        assertThat(limiter.tryAcquire("source-b")).isTrue();
        assertThat(limiter.tryAcquire("source-b")).isTrue();
        assertThat(limiter.tryAcquire("source-b")).isFalse();
    }

    // Verifies that getRemaining returns correct count
    @Test
    void getRemaining_afterConsumption_returnsCorrectCount() {
        CashRateLimiter limiter = createLimiter(10);

        assertThat(limiter.getRemaining("new-source")).isEqualTo(10);

        limiter.tryAcquire("new-source");
        limiter.tryAcquire("new-source");

        assertThat(limiter.getRemaining("new-source")).isEqualTo(8);
    }

    // Verifies that concurrent access is handled safely
    @Test
    void tryAcquire_concurrentAccess_respectsLimit() throws InterruptedException {
        CashRateLimiter limiter = createLimiter(50);
        int threadCount = 100;
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threadCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        if (limiter.tryAcquire("concurrent-source")) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertThat(successCount.get()).isEqualTo(50);
    }

    // Verifies that the limiter handles a large number of sources without error
    @Test
    void tryAcquire_manySources_handlesCorrectly() {
        CashRateLimiter limiter = createLimiter(5);

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("source-" + i)).isTrue();
        }
    }

    private CashRateLimiter createLimiter(int limit) {
        CashRateLimiter limiter = new CashRateLimiter();
        // Use reflection to set the rate limit value
        try {
            var field = CashRateLimiter.class.getDeclaredField("invoicesPerHour");
            field.setAccessible(true);
            field.setInt(limiter, limit);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set rate limit", e);
        }
        return limiter;
    }
}
