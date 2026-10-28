package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service rate limits on the public gifting endpoints (#410 "Abuse and privacy").
 *
 * <p>NOT a purchase cap (G6): it protects draft creation and the lookups behind it, keyed by
 * client IP and by recipient address. A token bucket per key, in memory: replicas each keep
 * their own, which only loosens the limit by the replica count, and nginx's limit zones sit in
 * front anyway.
 */
public class RateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, double refillPerSecond, Clock clock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        if (buckets.size() > 100_000) {
            buckets.clear();
        }
        double now = clock.millis() / 1000.0;
        double[] bucket = buckets.computeIfAbsent(key, k -> new double[] {capacity, now});
        synchronized (bucket) {
            bucket[0] = Math.min(capacity, bucket[0] + (now - bucket[1]) * refillPerSecond);
            bucket[1] = now;
            if (bucket[0] < 1) {
                return false;
            }
            bucket[0] -= 1;
            return true;
        }
    }
}
