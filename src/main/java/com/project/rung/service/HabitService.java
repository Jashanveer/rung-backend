package com.project.rung.service;

import com.project.rung.api.dto.HabitCreateRequest;
import com.project.rung.api.dto.HabitResponse;
import com.project.rung.api.dto.HabitUpdateRequest;
import com.project.rung.api.dto.TaskCreateRequest;
import com.project.rung.api.dto.TaskResponse;
import com.project.rung.api.dto.TaskUpdateRequest;
import com.project.rung.entity.Habit;
import com.project.rung.entity.HabitEntryType;
import com.project.rung.entity.HabitCheck;
import com.project.rung.entity.User;
import com.project.rung.repository.HabitCheckRepository;
import com.project.rung.repository.HabitRepository;
import com.project.rung.repository.RewardGrantRepository;
import com.project.rung.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final AccountabilityStreamService streamService;

    public HabitService(HabitRepository habitRepo, HabitCheckRepository checkRepo,
                        UserRepository userRepo, RewardService rewardService,
                        RewardGrantRepository rewardGrantRepo,
                        AccountabilityStreamService streamService) {
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.userRepo = userRepo;
        this.rewardService = rewardService;
        this.rewardGrantRepo = rewardGrantRepo;
        this.streamService = streamService;
    }

    /// Nudges every other SSE-subscribed device the user has open —
    /// they respond to the event by running their normal sync pass,
    /// so changes land across devices within seconds instead of on
    /// the next 5-minute timer tick. The event payload is intentionally
    /// minimal; reconciliation logic on the client already handles the
    /// data fetch.
    ///
    /// Defers to the transaction's `afterCommit` phase when one is
    /// active (which is every write path — public methods are
    /// `@Transactional`). That's critical: broadcasting mid-transaction
    /// would let subscribers sync against a pre-commit state that could
    /// still roll back, causing a "flash of stale data" until the next
    /// write. Deferring guarantees subscribers only ever fetch committed
    /// rows. Falls back to immediate broadcast if somehow called outside
    /// a transaction.
    private void broadcastHabitsChanged(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            streamService.publishToUser(userId, "habits.changed",
                                    Map.of("at", Instant.now().toString()));
                        }
                    }
            );
        } else {
            streamService.publishToUser(userId, "habits.changed",
                    Map.of("at", Instant.now().toString()));
        }
    }

    private User requireUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public List<HabitResponse> listHabits(Long userId) {
        return listByType(userId, HabitEntryType.HABIT);
    }

    public HabitResponse createHabit(Long userId, HabitCreateRequest req) {
        return createByType(
                userId, req.title(), req.reminderWindow(), HabitEntryType.HABIT,
                req.canonicalKey(), req.verificationTier(), req.verificationSource(),
                req.verificationParam(), req.weeklyTarget()
        );
    }

    @Transactional
    public HabitResponse updateHabit(Long userId, Long habitId, HabitUpdateRequest req) {
        return updateByType(
                userId, habitId, req.title(), req.reminderWindow(), HabitEntryType.HABIT,
                req.canonicalKey(), req.verificationTier(), req.verificationSource(),
                req.verificationParam(), req.weeklyTarget()
        );
    }

    @Transactional
    public void deleteHabit(Long userId, Long habitId) {
        deleteByType(userId, habitId, HabitEntryType.HABIT);
    }

    @Transactional
    public HabitResponse setCheck(Long userId, Long habitId, String dateKey, boolean done,
                                  String verificationTier, String verificationSource,
                                  Integer durationSeconds) {
        return setCheckByType(userId, habitId, dateKey, done, HabitEntryType.HABIT,
                verificationTier, verificationSource, durationSeconds);
    }

    public List<TaskResponse> listTasks(Long userId) {
        return listByType(userId, HabitEntryType.TASK).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    public TaskResponse createTask(Long userId, TaskCreateRequest req) {
        // Tasks do not carry verification metadata — pass nulls so the
        // response shape is uniform and nothing leaks into the tasks table.
        return toTaskResponse(createByType(
                userId, req.title(), null, HabitEntryType.TASK,
                null, null, null, null, null
        ));
    }

    @Transactional
    public TaskResponse updateTask(Long userId, Long taskId, TaskUpdateRequest req) {
        return toTaskResponse(updateByType(
                userId, taskId, req.title(), null, HabitEntryType.TASK,
                null, null, null, null, null
        ));
    }

    @Transactional
    public void deleteTask(Long userId, Long taskId) {
        deleteByType(userId, taskId, HabitEntryType.TASK);
    }

    @Transactional
    public TaskResponse setTaskCheck(Long userId, Long taskId, String dateKey, boolean done,
                                     Integer durationSeconds) {
        // Tasks don't participate in tier-weighted scoring — verification
        // metadata is always null for their checks.
        return toTaskResponse(setCheckByType(
                userId, taskId, dateKey, done, HabitEntryType.TASK, null, null,
                durationSeconds
        ));
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
            out.add(toHabitResponse(h, checksByHabitId.getOrDefault(h.getId(), Map.of())));
        }
        return out;
    }

    private HabitResponse createByType(Long userId, String title, String reminderWindow, HabitEntryType type,
                                       String canonicalKey, String verificationTier, String verificationSource,
                                       Double verificationParam, Integer weeklyTarget) {
        User user = requireUser(userId);
        Habit habit = Habit.builder()
                .user(user)
                .title(title)
                .reminderWindow(type == HabitEntryType.HABIT ? reminderWindow : null)
                .entryType(type)
                .canonicalKey(type == HabitEntryType.HABIT ? canonicalKey : null)
                .verificationTier(type == HabitEntryType.HABIT ? verificationTier : null)
                .verificationSource(type == HabitEntryType.HABIT ? verificationSource : null)
                .verificationParam(type == HabitEntryType.HABIT ? verificationParam : null)
                .weeklyTarget(type == HabitEntryType.HABIT ? weeklyTarget : null)
                .build();
        habitRepo.save(habit);
        broadcastHabitsChanged(userId);
        return toHabitResponse(habit, Map.of());
    }

    // @Transactional omitted here — Spring's AOP proxy does not intercept private calls,
    // so it would be a no-op. Every public caller is already @Transactional.
    private HabitResponse updateByType(Long userId, Long entryId, String title, String reminderWindow,
                                       HabitEntryType type, String canonicalKey, String verificationTier,
                                       String verificationSource, Double verificationParam, Integer weeklyTarget) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));

        habit.setTitle(title);
        habit.setReminderWindow(type == HabitEntryType.HABIT ? reminderWindow : null);
        // Verification metadata: null means "leave unchanged" — callers that
        // want to clear must send a dedicated null sentinel (not yet needed).
        if (type == HabitEntryType.HABIT) {
            if (canonicalKey != null) habit.setCanonicalKey(canonicalKey);
            if (verificationTier != null) habit.setVerificationTier(verificationTier);
            if (verificationSource != null) habit.setVerificationSource(verificationSource);
            if (verificationParam != null) habit.setVerificationParam(verificationParam);
            if (weeklyTarget != null) habit.setWeeklyTarget(weeklyTarget);
        }
        habitRepo.save(habit);
        broadcastHabitsChanged(userId);

        return toHabitResponse(habit, checksFor(habit));
    }

    // @Transactional omitted — see note on updateByType.
    private void deleteByType(Long userId, Long entryId, HabitEntryType type) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));
        rewardGrantRepo.deleteByHabit(habit);
        checkRepo.deleteAllByHabit(habit);
        habitRepo.delete(habit);
        broadcastHabitsChanged(userId);
    }

    // @Transactional omitted — see note on updateByType.
    private HabitResponse setCheckByType(Long userId, Long entryId, String dateKey, boolean done,
                                         HabitEntryType type, String verificationTier, String verificationSource,
                                         Integer durationSeconds) {
        User user = requireUser(userId);
        Habit habit = habitRepo.findByIdAndUserAndEntryType(entryId, user, type)
                .orElseThrow(() -> new IllegalArgumentException(entityLabel(type) + " not found"));

        // 1. Rate-limit guard — throws HTTP 429 if exceeded
        rewardService.checkRateLimit(userId);

        HabitCheck hc = loadOrCreateCheck(habit, dateKey);

        boolean wasAlreadyDone = hc.isDone();
        hc.setDone(done);
        if (done) {
            hc.setCompletedAt(Instant.now());
            // Record the verification tier/source for this specific check.
            // Only overwrite on done=true so an untoggle-then-retoggle flow
            // refreshes the tier; toggling off leaves the historical record.
            if (verificationTier != null) hc.setVerificationTier(verificationTier);
            if (verificationSource != null) hc.setVerificationSource(verificationSource);
            if (durationSeconds != null) hc.setDurationSeconds(durationSeconds);
        } else {
            hc.setCompletedAt(null);
            hc.setDurationSeconds(null);
        }
        try {
            checkRepo.save(hc);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Concurrent setCheck for the same (habit, dateKey) — common
            // when a user toggles on iPhone and macOS within milliseconds,
            // or when flushOutbox replays a check that another device
            // already wrote. The unique constraint
            // `uk(habit_id, date_key)` rejects the second insert; fetch
            // the row that won and re-apply the intended state on top.
            HabitCheck existing = checkRepo.findByHabitAndDateKey(habit, dateKey)
                    .orElseThrow(() -> race);
            existing.setDone(done);
            if (done) {
                existing.setCompletedAt(Instant.now());
                if (verificationTier != null) existing.setVerificationTier(verificationTier);
                if (verificationSource != null) existing.setVerificationSource(verificationSource);
                if (durationSeconds != null) existing.setDurationSeconds(durationSeconds);
            } else {
                existing.setCompletedAt(null);
                existing.setDurationSeconds(null);
            }
            checkRepo.save(existing);
            hc = existing;
        }

        // 2. Grant or revoke reward (idempotent; respects daily cap)
        if (done && !wasAlreadyDone) {
            rewardService.grantCheck(user, habit, dateKey);
        } else if (!done && wasAlreadyDone) {
            rewardService.revokeCheck(user, habit, dateKey);
        }

        broadcastHabitsChanged(userId);
        // Return the full check map for this habit so callers always have the real state
        return toHabitResponse(habit, checksFor(habit));
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

    /// Pulls the existing HabitCheck for (habit, dateKey) or builds a
    /// new one in-memory. Race-handling lives in the caller — two
    /// concurrent threads can both reach the `else` branch and produce
    /// independent transient HabitChecks; the unique-constraint catch
    /// in setCheckByType covers the second writer.
    private HabitCheck loadOrCreateCheck(Habit habit, String dateKey) {
        return checkRepo.findByHabitAndDateKey(habit, dateKey)
                .orElseGet(() -> HabitCheck.builder()
                        .habit(habit)
                        .dateKey(dateKey)
                        .done(false)
                        .build());
    }

    private HabitResponse toHabitResponse(Habit habit, Map<String, Boolean> checksByDate) {
        return new HabitResponse(
                habit.getId(),
                habit.getTitle(),
                habit.getReminderWindow(),
                checksByDate,
                habit.getCanonicalKey(),
                habit.getVerificationTier(),
                habit.getVerificationSource(),
                habit.getVerificationParam(),
                habit.getWeeklyTarget()
        );
    }
}
