package dev.devworld.server.community;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Token bucket на кожного гравця: {@code burst} дій підряд, далі одна на {@code refillNanos}. Не потокобезпечний. */
final class RateLimiter {

    private record Bucket(double tokens, long atNanos) {
    }

    private final int burst;
    private final long refillNanos;
    private final LongSupplier nanos;
    private final Map<String, Bucket> buckets = new HashMap<>();

    RateLimiter(int burst, long refillNanos, LongSupplier nanos) {
        this.burst = burst;
        this.refillNanos = refillNanos;
        this.nanos = nanos;
    }

    /** true — дію можна виконати (і її враховано). */
    boolean tryAcquire(String key) {
        long now = nanos.getAsLong();
        var bucket = buckets.getOrDefault(key, new Bucket(burst, now));
        double tokens = Math.min(burst, bucket.tokens + (double) (now - bucket.atNanos) / refillNanos);
        if (tokens < 1) {
            buckets.put(key, new Bucket(tokens, now));
            return false;
        }
        buckets.put(key, new Bucket(tokens - 1, now));
        return true;
    }

    void forget(String key) {
        buckets.remove(key);
    }
}
