package com.project.habit_tracker.service;

import com.project.habit_tracker.entity.StreakFreeze;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.StreakFreezeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class StreakFreezeService {

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

    public List<String> frozenDates(User user) {
        return freezeRepo.findAllByUser(user).stream()
                .filter(f -> f.getUsedForDateKey() != null)
                .map(StreakFreeze::getUsedForDateKey)
                .toList();
    }
}
