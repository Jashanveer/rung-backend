package com.project.habit_tracker.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP, per-operation token-bucket rate limiter for auth endpoints.
 *
 * NOTE: state is in-memory — effective for single-instance deployments only.
 * For multi-pod deployments replace with a Redis-backed implementation
 * (e.g. Redisson + bucket4j).
 */
@Component
public class AuthRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    // Separate limits per operation
    private static final int LOGIN_CAPACITY          = 10;
    private static final int REGISTER_CAPACITY       = 5;
    private static final int EMAIL_VERIF_CAPACITY    = 5;
    private static final int FORGOT_PW_CAPACITY      = 5;

    // Key = "ip:operation" -> bucket
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public void checkLogin(String ip)             { check(ip + ":login",    LOGIN_CAPACITY); }
    public void checkRegister(String ip)          { check(ip + ":register", REGISTER_CAPACITY); }
    public void checkEmailVerification(String ip) { check(ip + ":emailverif", EMAIL_VERIF_CAPACITY); }
    public void checkForgotPassword(String ip)    { check(ip + ":forgotpw", FORGOT_PW_CAPACITY); }

    private void check(String key, int capacity) {
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity));
        if (!bucket.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
        }
    }

    private static final class TokenBucket {
        private final int capacity;
        private final AtomicInteger tokens;
        private final AtomicLong windowEnd = new AtomicLong(System.currentTimeMillis() + WINDOW_MS);

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens   = new AtomicInteger(capacity);
        }

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now >= windowEnd.get()) {
                windowEnd.set(now + WINDOW_MS);
                tokens.set(capacity);
            }
            return tokens.getAndDecrement() > 0;
        }
    }
}
