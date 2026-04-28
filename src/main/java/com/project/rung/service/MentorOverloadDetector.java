package com.project.rung.service;

import com.project.rung.entity.Habit;
import com.project.rung.entity.HabitCheck;
import com.project.rung.entity.HabitEntryType;
import com.project.rung.entity.MentorMatch;
import com.project.rung.entity.MentorMatchStatus;
import com.project.rung.entity.User;
import com.project.rung.repository.HabitCheckRepository;
import com.project.rung.repository.HabitRepository;
import com.project.rung.repository.MentorMatchRepository;
import com.project.rung.service.MentorAI.OverloadAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily pass that flags mentees who look stretched too thin — too many
 * habits paired with sliding weekly consistency — and asks the AI mentor
 * (in the user's current rotating personality) to suggest pausing one.
 *
 * Thresholds are deliberately simple: {@code totalHabits > 6} AND weekly
 * consistency < 55%. The AI doesn't make the classification decision (free
 * tier is too rate-limited for that) — it only drafts the in-character copy
 * when the heuristic has already decided the user qualifies.
 */
@Component
public class MentorOverloadDetector {

    private static final Logger log = LoggerFactory.getLogger(MentorOverloadDetector.class);
    private static final int MIN_HABITS_FOR_OVERLOAD = 7;
    private static final int MAX_CONSISTENCY_FOR_OVERLOAD = 55;

    private final MentorMatchRepository matchRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository checkRepo;
    private final AccountabilityService accountabilityService;
    private final MentorAI mentorAI;

    public MentorOverloadDetector(
            MentorMatchRepository matchRepo,
            HabitRepository habitRepo,
            HabitCheckRepository checkRepo,
            AccountabilityService accountabilityService,
            MentorAI mentorAI
    ) {
        this.matchRepo = matchRepo;
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.accountabilityService = accountabilityService;
        this.mentorAI = mentorAI;
    }

    /// Runs at 08:30 UTC every day — before most users' MORNING window
    /// fires in North America, so the overload nudge lands as the first
    /// message of the day rather than on top of the windowed check-in.
    @Scheduled(cron = "0 30 8 * * *")
    public void scan() {
        if (!mentorAI.isConfigured()) return;

        List<MentorMatch> matches = matchRepo.findAllByStatus(MentorMatchStatus.ACTIVE);
        for (MentorMatch match : matches) {
            if (!match.isAiMentor()) continue;
            try {
                OverloadAssessment assessment = assess(match.getMentee());
                if (!assessment.overloaded()) continue;
                accountabilityService.aiOverloadNudge(match.getId(), assessment);
                log.debug("Fired overload nudge for match {} ({} habits at {}%)",
                        match.getId(), assessment.totalHabits(), assessment.weeklyConsistencyPercent());
            } catch (Exception ex) {
                log.warn("Overload scan failed for match {}: {}", match.getId(), ex.getMessage());
            }
        }
    }

    private OverloadAssessment assess(User user) {
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, HabitEntryType.HABIT);
        int totalHabits = habits.size();
        if (totalHabits < MIN_HABITS_FOR_OVERLOAD) {
            return new OverloadAssessment(false, totalHabits, 100, 0, List.of());
        }

        // Weekly consistency across the last 7 days.
        LocalDate today = LocalDate.now();
        List<HabitCheck> checks = checkRepo.findAllByHabitIn(habits);
        Map<String, Long> doneCountByDay = checks.stream()
                .filter(HabitCheck::isDone)
                .collect(Collectors.groupingBy(HabitCheck::getDateKey, Collectors.counting()));

        int hits = 0;
        for (int i = 0; i < 7; i++) {
            String key = today.minusDays(i).toString();
            hits += doneCountByDay.getOrDefault(key, 0L).intValue();
        }
        int maxPossible = totalHabits * 7;
        int consistency = maxPossible > 0 ? (int) Math.round(hits * 100.0 / maxPossible) : 0;

        // Missed today count (for the prompt).
        String todayKey = today.toString();
        int doneToday = (int) habits.stream()
                .filter(h -> checks.stream().anyMatch(c -> c.getHabit().getId().equals(h.getId())
                        && todayKey.equals(c.getDateKey()) && c.isDone()))
                .count();
        int missedToday = Math.max(totalHabits - doneToday, 0);

        // The 3 habits with lowest 7-day completion — the suggestions the
        // AI will reference when recommending a pause.
        List<String> weakest = habits.stream()
                .map(h -> Map.entry(h, completionRate(h, checks, today)))
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .map(e -> e.getKey().getTitle())
                .toList();

        boolean overloaded = consistency < MAX_CONSISTENCY_FOR_OVERLOAD;
        return new OverloadAssessment(overloaded, totalHabits, consistency, missedToday, weakest);
    }

    private double completionRate(Habit habit, List<HabitCheck> checks, LocalDate today) {
        int hits = 0;
        for (int i = 0; i < 7; i++) {
            String key = today.minusDays(i).toString();
            boolean done = checks.stream().anyMatch(c -> c.getHabit().getId().equals(habit.getId())
                    && key.equals(c.getDateKey()) && c.isDone());
            if (done) hits++;
        }
        return hits / 7.0;
    }
}
