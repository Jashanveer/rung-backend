package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.HabitCreateRequest;
import com.project.habit_tracker.api.dto.HabitResponse;
import com.project.habit_tracker.api.dto.HabitUpdateRequest;
import com.project.habit_tracker.api.dto.TaskCreateRequest;
import com.project.habit_tracker.api.dto.TaskResponse;
import com.project.habit_tracker.api.dto.TaskUpdateRequest;
import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitEntryType;
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
        return listByType(userId, HabitEntryType.HABIT);
    }

    public HabitResponse createHabit(Long userId, HabitCreateRequest req) {
        return createByType(userId, req.title(), req.reminderWindow(), HabitEntryType.HABIT);
    }

    @Transactional
    public HabitResponse updateHabit(Long userId, Long habitId, HabitUpdateRequest req) {
        return updateByType(userId, habitId, req.title(), req.reminderWindow(), HabitEntryType.HABIT);
    }

    @Transactional
    public void deleteHabit(Long userId, Long habitId) {
        deleteByType(userId, habitId, HabitEntryType.HABIT);
    }

    @Transactional
    public HabitResponse setCheck(Long userId, Long habitId, String dateKey, boolean done) {
        return setCheckByType(userId, habitId, dateKey, done, HabitEntryType.HABIT);
    }

    public List<TaskResponse> listTasks(Long userId) {
        return listByType(userId, HabitEntryType.TASK).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    public TaskResponse createTask(Long userId, TaskCreateRequest req) {
        return toTaskResponse(createByType(userId, req.title(), null, HabitEntryType.TASK));
    }

    @Transactional
    public TaskResponse updateTask(Long userId, Long taskId, TaskUpdateRequest req) {
        return toTaskResponse(updateByType(userId, taskId, req.title(), null, HabitEntryType.TASK));
    }

    @Transactional
    public void deleteTask(Long userId, Long taskId) {
        deleteByType(userId, taskId, HabitEntryType.TASK);
    }

    @Transactional
    public TaskResponse setTaskCheck(Long userId, Long taskId, String dateKey, boolean done) {
        return toTaskResponse(setCheckByType(userId, taskId, dateKey, done, HabitEntryType.TASK));
    }

    private List<HabitResponse> listByType(Long userId, HabitEntryType type) {
        User user = requireUser(userId);
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, type);

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

    private HabitResponse createByType(Long userId, String title, String reminderWindow, HabitEntryType type) {
        User user = requireUser(userId);
        Habit habit = Habit.builder()
                .user(user)
                .title(title)
                .reminderWindow(type == HabitEntryType.HABIT ? reminderWindow : null)
                .entryType(type)
                .build();
        habitRepo.save(habit);
        return new HabitResponse(habit.getId(), habit.getTitle(), habit.getReminderWindow(), Map.of());
    }

    // @Transactional omitted here — Spring's AOP proxy does not intercept private calls,
    // so it would be a no-op. Every public caller is already @Transactional.
    private HabitResponse updateByType(Long userId, Long entryId, String title, String reminderWindow, HabitEntryType type) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));

        habit.setTitle(title);
        habit.setReminderWindow(type == HabitEntryType.HABIT ? reminderWindow : null);
        habitRepo.save(habit);

        return new HabitResponse(
                habit.getId(),
                habit.getTitle(),
                habit.getReminderWindow(),
                checksFor(habit)
        );
    }

    // @Transactional omitted — see note on updateByType.
    private void deleteByType(Long userId, Long entryId, HabitEntryType type) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));
        rewardGrantRepo.deleteByHabit(habit);
        checkRepo.deleteAllByHabit(habit);
        habitRepo.delete(habit);
    }

    // @Transactional omitted — see note on updateByType.
    private HabitResponse setCheckByType(Long userId, Long entryId, String dateKey, boolean done, HabitEntryType type) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));

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

    private TaskResponse toTaskResponse(HabitResponse habitResponse) {
        return new TaskResponse(
                habitResponse.id(),
                habitResponse.title(),
                habitResponse.checksByDate()
        );
    }

    private String entityLabel(HabitEntryType type) {
        return type == HabitEntryType.HABIT ? "Habit" : "Task";
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
