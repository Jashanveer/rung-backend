package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.HabitCreateRequest;
import com.project.habit_tracker.api.dto.HabitResponse;
import com.project.habit_tracker.api.dto.HabitUpdateRequest;
import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitCheck;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.HabitCheckRepository;
import com.project.habit_tracker.repository.HabitRepository;
import com.project.habit_tracker.repository.RewardGrantRepository;
import com.project.habit_tracker.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HabitService {
    private final HabitRepository habitRepo;
    private final HabitCheckRepository checkRepo;
    private final UserRepository userRepo;
    private final RewardService rewardService;
    private final RewardGrantRepository rewardGrantRepo;

    public HabitService(HabitRepository habitRepo, HabitCheckRepository checkRepo,
                        UserRepository userRepo, RewardService rewardService,
                        RewardGrantRepository rewardGrantRepo) {
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.userRepo = userRepo;
        this.rewardService = rewardService;
        this.rewardGrantRepo = rewardGrantRepo;
    }

    private User requireUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public List<HabitResponse> listHabits(Long userId) {
        User user = requireUser(userId);
        List<Habit> habits = habitRepo.findAllByUser(user);

        List<HabitCheck> checks = habits.isEmpty() ? List.of() : checkRepo.findAllByHabitIn(habits);

        Map<Long, Map<String, Boolean>> checksByHabitId = new HashMap<>();
        for (HabitCheck c : checks) {
            if (!c.isDone()) continue; // only store true checks (matches your UI style)
            checksByHabitId.computeIfAbsent(c.getHabit().getId(), k -> new HashMap<>())
                    .put(c.getDateKey(), true);
        }

        List<HabitResponse> out = new ArrayList<>();
        for (Habit h : habits) {
            out.add(new HabitResponse(h.getId(), h.getTitle(), h.getReminderWindow(),
                    checksByHabitId.getOrDefault(h.getId(), Map.of())));
        }
        return out;
    }

    public HabitResponse createHabit(Long userId, HabitCreateRequest req) {
        User user = requireUser(userId);
        Habit habit = Habit.builder()
                .user(user)
                .title(req.title())
                .reminderWindow(req.reminderWindow())
                .build();
        habitRepo.save(habit);
        return new HabitResponse(habit.getId(), habit.getTitle(), habit.getReminderWindow(), Map.of());
    }

    @Transactional
    public HabitResponse updateHabit(Long userId, Long habitId, HabitUpdateRequest req) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));

        habit.setTitle(req.title());
        habit.setReminderWindow(req.reminderWindow());
        habitRepo.save(habit);

        return new HabitResponse(
                habit.getId(),
                habit.getTitle(),
                habit.getReminderWindow(),
                checksFor(habit)
        );
    }

    @Transactional
    public void deleteHabit(Long userId, Long habitId) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));
        rewardGrantRepo.deleteByHabit(habit);
        checkRepo.deleteAllByHabit(habit);
        habitRepo.delete(habit);
    }

    @Transactional
    public HabitResponse setCheck(Long userId, Long habitId, String dateKey, boolean done) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));

        // 1. Rate-limit guard — throws HTTP 429 if exceeded
        rewardService.checkRateLimit(userId);

        HabitCheck hc = checkRepo.findByHabitAndDateKey(habit, dateKey)
                .orElseGet(() -> HabitCheck.builder().habit(habit).dateKey(dateKey).done(false).build());

        boolean wasAlreadyDone = hc.isDone();
        hc.setDone(done);
        if (done) {
            hc.setCompletedAt(Instant.now());
        } else {
            hc.setCompletedAt(null);
        }
        checkRepo.save(hc);

        // 2. Grant or revoke reward (idempotent; respects daily cap)
        if (done && !wasAlreadyDone) {
            rewardService.grantCheck(user, habit, dateKey);
        } else if (!done && wasAlreadyDone) {
            rewardService.revokeCheck(user, habit, dateKey);
        }

        // Return the full check map for this habit so callers always have the real state
        return new HabitResponse(habit.getId(), habit.getTitle(), habit.getReminderWindow(), checksFor(habit));
    }

    private Map<String, Boolean> checksFor(Habit habit) {
        List<HabitCheck> allChecks = checkRepo.findAllByHabitIn(List.of(habit));
        Map<String, Boolean> map = new HashMap<>();
        for (HabitCheck c : allChecks) {
            if (c.isDone()) map.put(c.getDateKey(), true);
        }
        return map;
    }
}
