package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.HabitCreateRequest;
import com.project.habit_tracker.api.dto.HabitResponse;
import com.project.habit_tracker.api.dto.HabitUpdateRequest;
import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitCheck;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.HabitCheckRepository;
import com.project.habit_tracker.repository.HabitRepository;
import com.project.habit_tracker.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HabitService {
    private final HabitRepository habitRepo;
    private final HabitCheckRepository checkRepo;
    private final UserRepository userRepo;

    public HabitService(HabitRepository habitRepo, HabitCheckRepository checkRepo, UserRepository userRepo) {
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.userRepo = userRepo;
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
            out.add(new HabitResponse(h.getId(), h.getTitle(),
                    checksByHabitId.getOrDefault(h.getId(), Map.of())));
        }
        return out;
    }

    public HabitResponse createHabit(Long userId, HabitCreateRequest req) {
        User user = requireUser(userId);
        Habit habit = Habit.builder().user(user).title(req.title()).build();
        habitRepo.save(habit);
        return new HabitResponse(habit.getId(), habit.getTitle(), Map.of());
    }

    @Transactional
    public HabitResponse updateHabitTitle(Long userId, Long habitId, HabitUpdateRequest req) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));
        habit.setTitle(req.title());
        return new HabitResponse(habit.getId(), habit.getTitle(), Map.of());
    }

    @Transactional
    public void deleteHabit(Long userId, Long habitId) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));
        checkRepo.deleteAllByHabit(habit);
        habitRepo.delete(habit);
    }

    @Transactional
    public HabitResponse setCheck(Long userId, Long habitId, String dateKey, boolean done) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUser(habitId, user)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found"));

        HabitCheck hc = checkRepo.findByHabitAndDateKey(habit, dateKey)
                .orElseGet(() -> HabitCheck.builder().habit(habit).dateKey(dateKey).done(false).build());

        hc.setDone(done);
        checkRepo.save(hc);

        // Return updated habit shape
        Map<String, Boolean> map = new HashMap<>();
        if (done) map.put(dateKey, true);
        return new HabitResponse(habit.getId(), habit.getTitle(), map);
    }
}
