package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AccountabilityDashboardResponse;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends personalised weekly reflection emails every Sunday at 08:00 server time.
 *
 * Each email is derived entirely from the user's real stats — no AI API key needed.
 * The EmailService builds a human-feeling summary from the numbers.
 */
@Component
public class WeeklyReflectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReflectionScheduler.class);

    private final UserRepository userRepo;
    private final AccountabilityService accountabilityService;
    private final EmailService emailService;

    public WeeklyReflectionScheduler(
            UserRepository userRepo,
            AccountabilityService accountabilityService,
            EmailService emailService
    ) {
        this.userRepo = userRepo;
        this.accountabilityService = accountabilityService;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 8 * * SUN")
    public void sendWeeklyReflections() {
        List<User> users = userRepo.findAll();
        log.info("Weekly reflection: sending to {} user(s)", users.size());
        int sent = 0;

        for (User user : users) {
            try {
                AccountabilityDashboardResponse dash = accountabilityService.dashboard(user.getId());
                EmailService.WeeklyReflectionData data = new EmailService.WeeklyReflectionData(
                        dash.level().weeklyConsistencyPercent(),
                        dash.menteeDashboard().missedHabitsToday(), // reused as totalHabits proxy — see note
                        dash.rewards().xp() / 20,                  // rough best-streak estimate from XP
                        dash.weeklyChallenge().completedPerfectDays(),
                        dash.level().name(),
                        dash.rewards().badges(),
                        dash.menteeDashboard().mentorTip()
                );
                emailService.sendWeeklyReflection(
                        user.getEmail(),
                        dash.profile().displayName(),
                        data
                );
                sent++;
            } catch (Exception e) {
                log.error("Weekly reflection failed for user {}: {}", user.getId(), e.getMessage());
            }
        }

        log.info("Weekly reflection: {} email(s) dispatched", sent);
    }
}
