package com.project.rung.service;

import com.project.rung.entity.MentorMatch;
import com.project.rung.entity.MentorMatchStatus;
import com.project.rung.entity.MentorshipMessage;
import com.project.rung.entity.User;
import com.project.rung.entity.UserProfile;
import com.project.rung.repository.MentorMatchRepository;
import com.project.rung.repository.MentorshipMessageRepository;
import com.project.rung.repository.UserProfileRepository;
import com.project.rung.service.MentorAI.CheckInWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fires the 4 windowed AI-mentor check-ins per user per day. Each tick
 * (every 15 minutes) walks active AI matches and, for each mentee:
 *
 *   1. Computes which check-in window (if any) is currently active in the
 *      mentee's LOCAL timezone — MORNING / MIDDAY / EVENING / NIGHT.
 *   2. Skips if the mentee has nothing incomplete today (no habits → no nudge).
 *   3. Skips if the AI mentor already sent a message in the last
 *      {@link #MIN_GAP_MINUTES} minutes. This doubles as "one fire per
 *      window" (windows are spaced ≥ 3 h apart) and naturally lets a
 *      mid-window newly-added habit surface on the next tick.
 *
 * The actual generation + persistence is delegated to
 * {@link AccountabilityService#aiScheduledCheckIn(Long, CheckInWindow)}.
 */
@Component
public class MentorCheckInScheduler {

    private static final Logger log = LoggerFactory.getLogger(MentorCheckInScheduler.class);
    private static final long MIN_GAP_MINUTES = 120;

    private final MentorMatchRepository matchRepo;
    private final UserProfileRepository profileRepo;
    private final MentorshipMessageRepository messageRepo;
    private final AccountabilityService accountabilityService;
    private final MentorAI mentorAI;

    public MentorCheckInScheduler(
            MentorMatchRepository matchRepo,
            UserProfileRepository profileRepo,
            MentorshipMessageRepository messageRepo,
            AccountabilityService accountabilityService,
            MentorAI mentorAI
    ) {
        this.matchRepo = matchRepo;
        this.profileRepo = profileRepo;
        this.messageRepo = messageRepo;
        this.accountabilityService = accountabilityService;
        this.mentorAI = mentorAI;
    }

    @Scheduled(fixedRate = 15L * 60 * 1000)
    public void tick() {
        if (!mentorAI.isConfigured()) return;

        List<MentorMatch> matches = matchRepo.findAllByStatus(MentorMatchStatus.ACTIVE);
        for (MentorMatch match : matches) {
            if (!match.isAiMentor()) continue;

            try {
                User mentee = match.getMentee();
                UserProfile profile = profileRepo.findByUser(mentee).orElse(null);
                if (profile == null) continue;

                CheckInWindow window = activeWindow(profile.getTimezone());
                if (window == null) continue;

                // Skip if mentee has nothing open right now — no reason to nudge.
                int missed = accountabilityService.missedTodayFor(mentee);
                if (missed <= 0) continue;

                // Skip if the AI mentor just spoke — respects rate limits +
                // naturally enforces one-fire-per-window since the four
                // windows are > MIN_GAP_MINUTES apart.
                if (spokeRecently(match, MIN_GAP_MINUTES)) continue;

                accountabilityService.aiScheduledCheckIn(match.getId(), window);
                log.debug("Fired AI {} check-in for match {} (mentee {})", window, match.getId(), mentee.getId());
            } catch (Exception ex) {
                log.warn("AI check-in failed for match {}: {}", match.getId(), ex.getMessage());
            }
        }
    }

    /// Returns the currently-active window for the given timezone, or null
    /// when the local clock is between windows.
    static CheckInWindow activeWindow(String tz) {
        ZoneId zone;
        try {
            zone = ZoneId.of(tz == null || tz.isBlank() ? "UTC" : tz);
        } catch (Exception ex) {
            zone = ZoneId.of("UTC");
        }
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();

        if (between(now, 7, 0, 9, 0))   return CheckInWindow.MORNING;
        if (between(now, 12, 0, 13, 0)) return CheckInWindow.MIDDAY;
        if (between(now, 18, 0, 20, 0)) return CheckInWindow.EVENING;
        if (between(now, 21, 0, 22, 0)) return CheckInWindow.NIGHT;
        return null;
    }

    private static boolean between(LocalTime t, int fromH, int fromM, int toH, int toM) {
        LocalTime from = LocalTime.of(fromH, fromM);
        LocalTime to   = LocalTime.of(toH, toM);
        return !t.isBefore(from) && t.isBefore(to);
    }

    private boolean spokeRecently(MentorMatch match, long gapMinutes) {
        Optional<MentorshipMessage> mostRecent = messageRepo
                .findTop20ByMatchOrderByCreatedAtDesc(match)
                .stream()
                .filter(msg -> msg.getSender().getId().equals(match.getMentor().getId()))
                .findFirst();
        if (mostRecent.isEmpty()) return false;
        return Duration.between(mostRecent.get().getCreatedAt(), Instant.now()).toMinutes() < gapMinutes;
    }
}
