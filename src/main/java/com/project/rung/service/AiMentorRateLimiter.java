package com.project.rung.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user budget for user-driven Anthropic/Gemini calls.
 *
 * Without this gate any authenticated client can loop POSTs to
 * {@code AccountabilityService.sendMessage} and trigger one Anthropic call
 * per request — burning the API credit within minutes. The cap is generous
 * for normal use (one mentor reply on each user message, naturally bounded
 * by typing speed) and aggressive against scripted abuse.
 *
 * In-memory, single-instance only. If the deployment scales horizontally,
 * swap this for a Redis-backed bucket (Bucket4j on Redis) so the budget
 * is shared across pods.
 */
@Service
public class AiMentorRateLimiter {

    /** Tokens issued per window — i.e. max user-triggered AI replies per hour. */
    private static final int  WINDOW_CAPACITY  = 20;
    private static final long WINDOW_DURATION_MS = 60L * 60L * 1000L; // 1 hour

    private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Throws HTTP 429 if {@code userId} has used up their hourly AI budget.
     * Call this BEFORE publishing the AiMentor event so the work never queues.
     */
    public void checkAiBudget(Long userId) {
        TokenBucket bucket = buckets.computeIfAbsent(userId, id -> new TokenBucket());
        if (!bucket.tryAcquire()) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "You're chatting with the mentor too quickly. Try again in a few minutes."
            );
        }
    }

    private static final class TokenBucket {
        private final AtomicInteger tokens    = new AtomicInteger(WINDOW_CAPACITY);
        private final AtomicLong    windowEnd = new AtomicLong(
            System.currentTimeMillis() + WINDOW_DURATION_MS
        );

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now >= windowEnd.get()) {
                windowEnd.set(now + WINDOW_DURATION_MS);
                tokens.set(WINDOW_CAPACITY);
            }
            return tokens.getAndDecrement() > 0;
        }
    }
}
