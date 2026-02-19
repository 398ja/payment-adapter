package xyz.tcheeric.payment.adapter.cash.gateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory token bucket rate limiter for cash invoice creation.
 * Limits the number of invoices per hour per source identifier.
 */
@Slf4j
@Component
public class CashRateLimiter {

    @Value("${cash.ratelimit.invoices-per-hour:100}")
    private int invoicesPerHour;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check if a request is allowed under the rate limit.
     *
     * @param source identifier for the requestor (e.g., IP address)
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String source) {
        TokenBucket bucket = buckets.computeIfAbsent(source, k -> new TokenBucket(invoicesPerHour));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.warn("Rate limit exceeded for source: {}", source);
        }
        return allowed;
    }

    /**
     * Get remaining tokens for a source.
     */
    public int getRemaining(String source) {
        TokenBucket bucket = buckets.get(source);
        return bucket != null ? bucket.getRemaining() : invoicesPerHour;
    }

    /**
     * Simple token bucket implementation with hourly refill.
     */
    private static class TokenBucket {
        private final int maxTokens;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefillTime;

        TokenBucket(int maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = new AtomicInteger(maxTokens);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        boolean tryConsume() {
            refillIfNeeded();
            int current = tokens.get();
            while (current > 0) {
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
                current = tokens.get();
            }
            return false;
        }

        int getRemaining() {
            refillIfNeeded();
            return tokens.get();
        }

        private void refillIfNeeded() {
            long now = System.currentTimeMillis();
            long lastRefill = lastRefillTime.get();
            long elapsedMs = now - lastRefill;

            if (elapsedMs >= 3_600_000) { // 1 hour
                if (lastRefillTime.compareAndSet(lastRefill, now)) {
                    tokens.set(maxTokens);
                }
            }
        }
    }
}
