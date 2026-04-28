package com.project.rung.service;

import com.project.rung.entity.StreakFreeze;
import com.project.rung.entity.User;
import com.project.rung.repository.StreakFreezeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class StreakFreezeService {

    /// Window during which the most recent freeze usage can be undone by the
    /// user. Matches the 5-second on-screen undo banner. Longer windows make
    /// the feature exploitable for streak manipulation; shorter windows risk
    /// racing network latency between the Use and Undo taps.
    private static final long UNDO_WINDOW_SECONDS = 5;

    private final StreakFreezeRepository freezeRepo;

    public StreakFreezeService(StreakFreezeRepository freezeRepo) {
        this.freezeRepo = freezeRepo;
    }

    public int availableFreezes(User user) {
        return (int) freezeRepo.countByUserAndUsedAtIsNull(user);
    }

    @Transactional
    public void earnFreeze(User user) {
        // Idempotency: don't grant if a freeze was already granted in the last 7 days
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        boolean alreadyGrantedThisWeek = freezeRepo.findAllByUser(user).stream()
                .anyMatch(f -> f.getGrantedAt().isAfter(sevenDaysAgo));
        if (alreadyGrantedThisWeek) {
            return;
        }
        StreakFreeze freeze = StreakFreeze.builder()
                .user(user)
                .grantedAt(Instant.now())
                .usedAt(null)
                .usedForDateKey(null)
                .build();
        freezeRepo.save(freeze);
    }

    @Transactional
    public boolean useFreeze(User user, String dateKey) {
        List<StreakFreeze> available = freezeRepo.findAllByUser(user).stream()
                .filter(f -> f.getUsedAt() == null)
                .toList();
        if (available.isEmpty()) {
            return false;
        }
        StreakFreeze freeze = available.get(0);
        freeze.setUsedAt(Instant.now());
        freeze.setUsedForDateKey(dateKey);
        freezeRepo.save(freeze);
        return true;
    }

    /// Reverts the user's most recent freeze usage if it happened within the
    /// UNDO_WINDOW_SECONDS grace period. Returns true if a freeze was reverted,
    /// false if there was nothing to undo or the window has elapsed.
    @Transactional
    public boolean undoLastFreeze(User user) {
        Optional<StreakFreeze> mostRecent = freezeRepo.findAllByUser(user).stream()
                .filter(f -> f.getUsedAt() != null)
                .max(Comparator.comparing(StreakFreeze::getUsedAt));
        if (mostRecent.isEmpty()) {
            return false;
        }
        StreakFreeze freeze = mostRecent.get();
        Instant cutoff = Instant.now().minus(UNDO_WINDOW_SECONDS, ChronoUnit.SECONDS);
        if (freeze.getUsedAt().isBefore(cutoff)) {
            return false;
        }
        freeze.setUsedAt(null);
        freeze.setUsedForDateKey(null);
        freezeRepo.save(freeze);
        return true;
    }

    public List<String> frozenDates(User user) {
        return freezeRepo.findAllByUser(user).stream()
                .filter(f -> f.getUsedForDateKey() != null)
                .map(StreakFreeze::getUsedForDateKey)
                .toList();
    }
}
