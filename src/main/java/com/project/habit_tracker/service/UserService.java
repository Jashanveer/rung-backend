package com.project.habit_tracker.service;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitCheck;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository habitCheckRepo;
    private final DeviceTokenRepository deviceTokenRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final EmailService emailService;

    public UserService(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            HabitRepository habitRepo,
            HabitCheckRepository habitCheckRepo,
            DeviceTokenRepository deviceTokenRepo,
            RefreshTokenRepository refreshTokenRepo,
            EmailService emailService
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.habitRepo = habitRepo;
        this.habitCheckRepo = habitCheckRepo;
        this.deviceTokenRepo = deviceTokenRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.emailService = emailService;
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Habit> habits = habitRepo.findAllByUser(user);

        // Compute farewell stats before data is gone
        int totalHabits = habits.size();
        List<HabitCheck> allChecks = habitCheckRepo.findAllByHabitIn(habits);
        int totalDaysTracked = (int) allChecks.stream()
                .map(HabitCheck::getDateKey).distinct().count();
        int bestStreak = (int) allChecks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.getHabit().getId(), java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(Long::longValue).max().orElse(0L);

        String displayName = user.getUsername() != null ? user.getUsername() : user.getEmail();

        // Delete in FK dependency order
        for (Habit habit : habits) {
            habitCheckRepo.deleteAllByHabit(habit);
        }
        habitRepo.deleteAllByUser(user);
        deviceTokenRepo.deleteAllByUser(user);
        refreshTokenRepo.deleteByUser(user);
        profileRepo.deleteByUser(user);
        userRepo.delete(user);

        // Send after flush so email failure doesn't roll back deletion
        emailService.sendAccountDeleted(user.getEmail(), displayName, totalHabits, bestStreak, totalDaysTracked);
    }
}
