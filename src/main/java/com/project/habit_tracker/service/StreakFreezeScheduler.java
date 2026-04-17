package com.project.habit_tracker.service;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitCheck;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.HabitCheckRepository;
import com.project.habit_tracker.repository.HabitRepository;
import com.project.habit_tracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class StreakFreezeScheduler {

    private static final Logger log = LoggerFactory.getLogger(StreakFreezeScheduler.class);

    private final UserRepository userRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository checkRepo;
    private final StreakFreezeService streakFreezeService;

    public StreakFreezeScheduler(
            UserRepository userRepo,
            HabitRepository habitRepo,
            HabitCheckRepository checkRepo,
            StreakFreezeService streakFreezeService
    ) {
        this.userRepo = userRepo;
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.streakFreezeService = streakFreezeService;
    }

    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void grantFreezesForPerfectWeek() {
        List<User> users = userRepo.findAll();
        for (User user : users) {
            try {
                if (hadPerfectWeek(user)) {
                    streakFreezeService.earnFreeze(user);
                    log.debug("Granted streak freeze to user {}", user.getId());
                }
            } catch (Exception e) {
                log.error("Error granting streak freeze to user {}: {}", user.getId(), e.getMessage());
            }
        }
    }

    private boolean hadPerfectWeek(User user) {
        List<Habit> habits = habitRepo.findAllByUser(user);
        if (habits.isEmpty()) return false;

        List<HabitCheck> checks = checkRepo.findAllByHabitIn(habits);

        // Build a set of date keys where all habits were done
        Map<String, Long> doneCountByDay = checks.stream()
                .filter(HabitCheck::isDone)
                .collect(Collectors.groupingBy(HabitCheck::getDateKey, Collectors.counting()));

        int totalHabits = habits.size();
        LocalDate today = LocalDate.now();

        // Check that all 7 of the last 7 days are perfect days
        for (int i = 0; i < 7; i++) {
            String key = today.minusDays(i).toString();
            long done = doneCountByDay.getOrDefault(key, 0L);
            if (done < totalHabits) {
                return false;
            }
        }
        return true;
    }
}
