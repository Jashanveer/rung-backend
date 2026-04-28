package com.project.rung.service;

import com.project.rung.entity.Habit;
import com.project.rung.entity.HabitEntryType;
import com.project.rung.entity.HabitCheck;
import com.project.rung.entity.User;
import com.project.rung.entity.UserProfile;
import com.project.rung.repository.HabitCheckRepository;
import com.project.rung.repository.HabitRepository;
import com.project.rung.repository.UserProfileRepository;
import com.project.rung.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sends personalised weekly HTML report emails every Sunday at 09:00 server time.
 */
@Component
public class WeeklyReflectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReflectionScheduler.class);
    private static final DateTimeFormatter KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("MMM d");

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository habitCheckRepo;
    private final EmailService emailService;
    private final MentorAI mentorAI;
    private final PersonalityRotator personalityRotator;

    public WeeklyReflectionScheduler(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            HabitRepository habitRepo,
            HabitCheckRepository habitCheckRepo,
            EmailService emailService,
            MentorAI mentorAI,
            PersonalityRotator personalityRotator
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.habitRepo = habitRepo;
        this.habitCheckRepo = habitCheckRepo;
        this.emailService = emailService;
        this.mentorAI = mentorAI;
        this.personalityRotator = personalityRotator;
    }

    @Scheduled(cron = "0 0 9 * * SUN")
    public void sendWeeklyReports() {
        // Last 7 days: Mon–Sun (today = Sunday)
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        List<LocalDate> weekDays = weekStart.datesUntil(today.plusDays(1)).toList();
        List<String> dayKeys = weekDays.stream().map(d -> d.format(KEY_FMT)).toList();
        List<String> dayLabels = weekDays.stream()
                .map(d -> d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .toList();
        String weekRange = weekStart.format(DISPLAY_FMT) + " – " + today.format(DISPLAY_FMT);

        // Percentile: compute all users' consistency so we can rank
        List<User> users = userRepo.findAll();
        Map<Long, Integer> userConsistency = new HashMap<>();
        for (User u : users) {
            try {
                List<Habit> habits = habitRepo.findAllByUserAndEntryType(u, HabitEntryType.HABIT);
                if (habits.isEmpty()) { userConsistency.put(u.getId(), 0); continue; }
                List<HabitCheck> checks = habitCheckRepo.findAllByHabitIn(habits);
                userConsistency.put(u.getId(), computeConsistency(habits, checks, dayKeys));
            } catch (Exception e) {
                userConsistency.put(u.getId(), 0);
            }
        }

        log.info("Weekly report: sending to {} user(s)", users.size());
        int sent = 0;

        for (User user : users) {
            try {
                // Honour the user-controlled email opt-out from
                // /api/users/me/preferences. Missing profiles fall through
                // to the previous behaviour (opt-in by default).
                boolean optedIn = profileRepo.findByUser(user)
                        .map(UserProfile::isEmailOptIn)
                        .orElse(true);
                if (!optedIn) continue;

                List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, HabitEntryType.HABIT);
                if (habits.isEmpty()) continue;

                List<HabitCheck> checks = habitCheckRepo.findAllByHabitIn(habits);
                Set<String> doneSet = checks.stream()
                        .filter(HabitCheck::isDone)
                        .map(c -> c.getHabit().getId() + ":" + c.getDateKey())
                        .collect(Collectors.toSet());

                // Daily completion rates (% of habits done each day)
                List<Integer> dailyRates = new ArrayList<>();
                int perfectDays = 0;
                for (String key : dayKeys) {
                    long done = habits.stream()
                            .filter(h -> doneSet.contains(h.getId() + ":" + key)).count();
                    int rate = (int) (done * 100 / habits.size());
                    dailyRates.add(rate);
                    if (rate == 100) perfectDays++;
                }

                // Overall weekly consistency
                int consistency = computeConsistency(habits, checks, dayKeys);

                // Best streak (max consecutive done days this week across all habits)
                int bestStreak = computeBestStreak(habits, checks, dayKeys);

                // Per-habit breakdown
                List<EmailService.HabitWeekStat> breakdown = new ArrayList<>();
                List<String> habitNames = new ArrayList<>();
                for (Habit h : habits) {
                    List<Boolean> dailyDone = dayKeys.stream()
                            .map(k -> doneSet.contains(h.getId() + ":" + k))
                            .toList();
                    long doneDays = dailyDone.stream().filter(b -> b).count();
                    int habitRate = (int) (doneDays * 100 / dayKeys.size());
                    breakdown.add(new EmailService.HabitWeekStat(h.getTitle(), habitRate, dailyDone));
                    if (habitNames.size() < 5) habitNames.add(h.getTitle());
                }

                // Percentile rank
                int myConsistency = userConsistency.getOrDefault(user.getId(), 0);
                long betterThan = userConsistency.values().stream().filter(v -> v < myConsistency).count();
                int percentile = users.size() > 1 ? (int) (betterThan * 100 / (users.size() - 1)) : 100;
                percentile = Math.min(99, Math.max(1, percentile));

                // Greet the user by their profile-set displayName when one
                // exists, falling back to the chosen username (NOT the
                // email-slug auto-generated handle, which would leak as
                // a random-looking string for private-relay sign-ups).
                // The username is at least the user's own pick.
                UserProfile userProfile = profileRepo.findByUser(user).orElse(null);
                String displayName;
                if (userProfile != null
                        && userProfile.getDisplayName() != null
                        && !userProfile.getDisplayName().isBlank()) {
                    displayName = userProfile.getDisplayName();
                } else if (user.getUsername() != null && !user.getUsername().isBlank()) {
                    displayName = user.getUsername();
                } else {
                    displayName = "there";
                }

                // AI insight — rendered in the user's current rotating
                // personality so the Sunday email echoes the same voice
                // they've been hearing in chat all week.
                MentorAI.MentorPersonality personality = userProfile != null
                        ? personalityRotator.currentPersonalityFor(userProfile)
                        : MentorAI.MentorPersonality.COACH;
                String aiInsight = mentorAI.generateWeeklyInsight(
                        displayName, consistency, perfectDays, bestStreak, habits.size(), habitNames, personality);

                EmailService.WeeklyReflectionData data = new EmailService.WeeklyReflectionData(
                        consistency, habits.size(), bestStreak, perfectDays,
                        levelLabel(consistency), List.of(), null,
                        weekRange, dayLabels, dailyRates, breakdown, percentile, aiInsight
                );

                emailService.sendWeeklyReport(user.getEmail(), displayName, data);
                sent++;
            } catch (Exception e) {
                log.error("Weekly report failed for user {}: {}", user.getId(), e.getMessage());
            }
        }

        log.info("Weekly report: {} email(s) dispatched", sent);
    }

    private int computeConsistency(List<Habit> habits, List<HabitCheck> checks, List<String> dayKeys) {
        if (habits.isEmpty() || dayKeys.isEmpty()) return 0;
        Set<String> doneSet = checks.stream()
                .filter(HabitCheck::isDone)
                .map(c -> c.getHabit().getId() + ":" + c.getDateKey())
                .collect(Collectors.toSet());
        long total = (long) habits.size() * dayKeys.size();
        long done = habits.stream()
                .flatMap(h -> dayKeys.stream().filter(k -> doneSet.contains(h.getId() + ":" + k)))
                .count();
        return (int) (done * 100 / total);
    }

    private int computeBestStreak(List<Habit> habits, List<HabitCheck> checks, List<String> dayKeys) {
        Set<String> doneSet = checks.stream()
                .filter(HabitCheck::isDone)
                .map(c -> c.getHabit().getId() + ":" + c.getDateKey())
                .collect(Collectors.toSet());
        int best = 0;
        for (Habit h : habits) {
            int streak = 0;
            for (String k : dayKeys) {
                if (doneSet.contains(h.getId() + ":" + k)) { streak++; best = Math.max(best, streak); }
                else streak = 0;
            }
        }
        return best;
    }

    private String levelLabel(int pct) {
        if (pct >= 90) return "Elite";
        if (pct >= 70) return "Strong";
        if (pct >= 40) return "Building";
        return "Starting";
    }
}
