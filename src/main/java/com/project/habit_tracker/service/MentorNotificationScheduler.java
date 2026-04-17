package com.project.habit_tracker.service;

import com.project.habit_tracker.entity.DeviceToken;
import com.project.habit_tracker.entity.MentorMatch;
import com.project.habit_tracker.entity.MentorMatchStatus;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import com.project.habit_tracker.repository.MentorMatchRepository;
import com.project.habit_tracker.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MentorNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(MentorNotificationScheduler.class);

    private final MentorMatchRepository matchRepo;
    private final AccountabilityService accountabilityService;
    private final DeviceTokenService deviceTokenService;
    private final ApnsService apnsService;
    private final UserProfileRepository profileRepo;

    // In-memory rate-limit: matchIds already notified today
    private final Set<Long> notifiedToday = new HashSet<>();

    public MentorNotificationScheduler(
            MentorMatchRepository matchRepo,
            AccountabilityService accountabilityService,
            DeviceTokenService deviceTokenService,
            ApnsService apnsService,
            UserProfileRepository profileRepo
    ) {
        this.matchRepo = matchRepo;
        this.accountabilityService = accountabilityService;
        this.deviceTokenService = deviceTokenService;
        this.apnsService = apnsService;
        this.profileRepo = profileRepo;
    }

    /** Clear the notified-today set at 00:01 every day. */
    @Scheduled(cron = "0 1 0 * * *")
    public void clearNotifiedToday() {
        notifiedToday.clear();
        log.debug("Cleared mentor notification rate-limit set for new day.");
    }

    /** At 20:00 every day, notify mentors whose mentees have missed habits today. */
    @Scheduled(cron = "0 0 20 * * *")
    @Transactional(readOnly = true)
    public void notifyMentorsOfMissedHabits() {
        List<MentorMatch> activeMatches = matchRepo.findAllByStatusIn(List.of(MentorMatchStatus.ACTIVE));
        for (MentorMatch match : activeMatches) {
            if (notifiedToday.contains(match.getId())) {
                continue;
            }
            try {
                User mentee = match.getMentee();
                int missedToday = accountabilityService.missedTodayFor(mentee);
                if (missedToday > 0) {
                    String menteeDisplayName = profileRepo.findByUser(mentee)
                            .map(UserProfile::getDisplayName)
                            .orElse("Your mentee");
                    String body = menteeDisplayName + " missed " + missedToday
                            + " habit" + (missedToday > 1 ? "s" : "") + " today.";
                    User mentor = match.getMentor();
                    List<DeviceToken> tokens = deviceTokenService.tokensForUser(mentor);
                    for (DeviceToken dt : tokens) {
                        apnsService.sendNudge(dt.getToken(), "Habit Tracker", body);
                    }
                    notifiedToday.add(match.getId());
                    log.debug("Notified mentor {} about mentee {} ({} missed habits)", mentor.getId(), mentee.getId(), missedToday);
                }
            } catch (Exception e) {
                log.error("Error notifying mentor for match {}: {}", match.getId(), e.getMessage());
            }
        }
    }
}
