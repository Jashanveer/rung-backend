package com.project.habit_tracker.service;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.RewardGrant;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.RewardGrantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anti-abuse reward layer — sits between HabitService and the DB.
 *
 * Three lines of defence:
 *   1. Per-user, per-minute token-bucket rate limit (20 check calls / minute).
 *   2. Per-user, per-day grant cap (15 unique habit-day combos earn XP; extras are silently allowed
 *      but yield 0 XP — users can still track habits beyond the cap).
 *   3. Idempotency via a unique DB constraint on (user_id, habit_id, date_key):
 *      a duplicate grant attempt is a no-op rather than double-awarding.
 *
 * XP constants live here so the dashboard and check endpoint share the same values.
 */
@Service
public class RewardService {

    // ── Public constants (consumed by AccountabilityService for display) ──────
    public static final int XP_PER_CHECK      = 12;
    public static final int DAILY_GRANT_CAP   = 15;   // unique habit-day combos that earn rewards per day

    // ── Rate-limit constants ───────────────────────────────────────────────────
    private static final int    RATE_LIMIT_CAPACITY    = 20;      // tokens per window
    private static final long   RATE_LIMIT_WINDOW_MS   = 60_000L; // 1 minute

    private final RewardGrantRepository rewardGrantRepo;

    // One token-bucket per authenticated userId; buckets are lazily created.
    private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RewardService(RewardGrantRepository rewardGrantRepo) {
        this.rewardGrantRepo = rewardGrantRepo;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Enforce the per-user rate limit before any check mutation.
     * Throws HTTP 429 if the user has exhausted their tokens for the current window.
     */
    public void checkRateLimit(Long userId) {
        TokenBucket bucket = buckets.computeIfAbsent(userId, id -> new TokenBucket());
        if (!bucket.tryAcquire()) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "You're toggling habits too fast. Please slow down a little."
            );
        }
    }

    /**
     * Grant XP for marking a habit done on {@code dateKey}.
     * Idempotent: a second call for the same (user, habit, dateKey) is a safe no-op.
     * No-op (0 XP) if the user has already hit the daily grant cap.
     *
     * @return the grant that was created, or the existing one if already granted
     */
    @Transactional
    public RewardGrant grantCheck(User user, Habit habit, String dateKey) {
        // Return existing grant if already done (idempotency)
        return rewardGrantRepo.findByUserAndHabitAndDateKey(user, habit, dateKey)
            .orElseGet(() -> createGrant(user, habit, dateKey));
    }

    /**
     * Revoke the reward for unchecking a habit.
     * Idempotent: calling this when no grant exists is a safe no-op.
     */
    @Transactional
    public void revokeCheck(User user, Habit habit, String dateKey) {
        rewardGrantRepo.deleteByUserAndHabitAndDateKey(user, habit, dateKey);
    }

    /** Total XP earned by this user across all time (sum of persisted grants). */
    public int totalXp(User user) {
        return rewardGrantRepo.sumXpByUser(user);
    }

    /** Number of unique habit-day grants earned today. */
    public int checksGrantedToday(User user) {
        return (int) rewardGrantRepo.countByUserAndDateKey(user, LocalDate.now().toString());
    }

    /** Whether the next check will earn XP (i.e., daily cap not yet reached). */
    public boolean isRewardEligible(User user) {
        return checksGrantedToday(user) < DAILY_GRANT_CAP;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private RewardGrant createGrant(User user, Habit habit, String dateKey) {
        // Check daily cap — if exceeded, grant 0 XP (still records the row for idempotency
        // so a retry doesn't re-check eligibility and accidentally grant on a later retry)
        long todayCount = rewardGrantRepo.countByUserAndDateKey(user, LocalDate.now().toString());
        int xp = todayCount < DAILY_GRANT_CAP ? XP_PER_CHECK : 0;

        RewardGrant grant = RewardGrant.builder()
            .user(user)
            .habit(habit)
            .dateKey(dateKey)
            .xpGranted(xp)
            .grantedAt(Instant.now())
            .build();

        try {
            return rewardGrantRepo.save(grant);
        } catch (DataIntegrityViolationException e) {
            // Race: another thread inserted the same row between our findBy and save.
            // Re-fetch and return the winner — still idempotent.
            return rewardGrantRepo
                .findByUserAndHabitAndDateKey(user, habit, dateKey)
                .orElseThrow(() -> new IllegalStateException("Reward grant conflict but row missing", e));
        }
    }

    // ── Token-bucket rate limiter (no external deps) ───────────────────────────

    private static final class TokenBucket {
        private final AtomicInteger tokens    = new AtomicInteger(RATE_LIMIT_CAPACITY);
        private final AtomicLong    windowEnd = new AtomicLong(
            System.currentTimeMillis() + RATE_LIMIT_WINDOW_MS
        );

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            // Refill when the window expires
            if (now >= windowEnd.get()) {
                windowEnd.set(now + RATE_LIMIT_WINDOW_MS);
                tokens.set(RATE_LIMIT_CAPACITY);
            }
            return tokens.getAndDecrement() > 0;
        }
    }
}
