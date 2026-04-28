package com.project.rung.service;

import com.project.rung.api.dto.AccountabilityDashboardResponse;
import com.project.rung.api.dto.MentorshipMessageRequest;
import com.project.rung.api.dto.ProfileRequest;
import com.project.rung.entity.*;
import com.project.rung.repository.*;
import com.project.rung.service.event.AiMentorWelcomeRequestedEvent;
import com.project.rung.service.event.MenteeMessageReceivedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccountabilityService {
    private static final int MENTOR_LOCK_DAYS = 7;
    private static final int MENTOR_DATA_DAYS = 7;
    private static final int MENTOR_INACTIVE_DAYS = 3;
    private static final int MENTOR_RATING_DEFAULT = 50;
    private static final int MENTOR_RATING_MIN = 0;
    private static final int MENTOR_RATING_MAX = 100;
    private static final int AI_REPLY_MAX_CHARS = 500;
    private static final int AI_HISTORY_TURNS = 10;
    static final String AI_MENTOR_EMAIL = "ai-mentor@rung.app";
    static final String AI_MENTOR_USERNAME = "forma_ai_mentor";
    static final String AI_MENTOR_FALLBACK_WELCOME =
            "Hi — I'm Rung's AI mentor while we look for a human match. Tell me one habit you want to land today and I'll help you protect it.";
    private static final List<MentorMatchStatus> LIVE_MATCH_STATUSES = List.of(
            MentorMatchStatus.PENDING,
            MentorMatchStatus.ACTIVE
    );

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository checkRepo;
    private final MentorMatchRepository matchRepo;
    private final MentorshipMessageRepository messageRepo;
    private final SocialPostRepository postRepo;
    private final FriendConnectionRepository friendRepo;
    private final AccountabilityStreamService streamService;
    private final DeviceTokenService deviceTokenService;
    private final ApnsService apnsService;
    private final RewardService rewardService;
    private final StreakFreezeService streakFreezeService;
    private final EmailService emailService;
    private final MentorAI mentorAI;
    private final PersonalityRotator personalityRotator;
    private final ApplicationEventPublisher eventPublisher;
    private final AiMentorRateLimiter aiMentorRateLimiter;

    public AccountabilityService(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            HabitRepository habitRepo,
            HabitCheckRepository checkRepo,
            MentorMatchRepository matchRepo,
            MentorshipMessageRepository messageRepo,
            SocialPostRepository postRepo,
            FriendConnectionRepository friendRepo,
            AccountabilityStreamService streamService,
            DeviceTokenService deviceTokenService,
            ApnsService apnsService,
            RewardService rewardService,
            StreakFreezeService streakFreezeService,
            EmailService emailService,
            MentorAI mentorAI,
            PersonalityRotator personalityRotator,
            ApplicationEventPublisher eventPublisher,
            AiMentorRateLimiter aiMentorRateLimiter
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.matchRepo = matchRepo;
        this.messageRepo = messageRepo;
        this.postRepo = postRepo;
        this.friendRepo = friendRepo;
        this.streamService = streamService;
        this.deviceTokenService = deviceTokenService;
        this.apnsService = apnsService;
        this.rewardService = rewardService;
        this.streakFreezeService = streakFreezeService;
        this.emailService = emailService;
        this.mentorAI = mentorAI;
        this.personalityRotator = personalityRotator;
        this.eventPublisher = eventPublisher;
        this.aiMentorRateLimiter = aiMentorRateLimiter;
    }

    @Transactional
    public AccountabilityDashboardResponse dashboard(Long userId) {
        User user = requireUser(userId);
        UserProfile profile = ensureProfile(user);

        Optional<MentorMatch> ownMatch = matchRepo.findFirstByMenteeAndStatusInOrderByCreatedAtDesc(user, LIVE_MATCH_STATUSES);
        // AI mentor is unlocked immediately for every user — no 7-day wait
        // and no human-matching pre-check. Restore the canFindMentor(stats)
        // gate if real-person mentor matching is re-enabled.
        if (ownMatch.isEmpty()) {
            ownMatch = autoAssignMentor(user, profile);
        }
        List<MentorMatch> mentorMatches = matchRepo.findAllByMentorAndStatusIn(user, LIVE_MATCH_STATUSES);

        // SQL-filtered candidate pool — excludes the viewer and the seeded AI
        // mentor in one round-trip instead of a full scan + in-memory filter.
        List<User> socialCandidates = userRepo.findAllSocialCandidates(user.getId(), AI_MENTOR_USERNAME);
        List<User> following = followingUsers(user);

        // One stats batch covers every user this dashboard renders — viewer,
        // mentor, all mentees, every follow, every social suggestion.
        Set<Long> statIds = new HashSet<>();
        statIds.add(user.getId());
        mentorMatches.forEach(m -> statIds.add(m.getMentee().getId()));
        ownMatch.ifPresent(m -> {
            statIds.add(m.getMentor().getId());
            statIds.add(m.getMentee().getId());
        });
        socialCandidates.forEach(u -> statIds.add(u.getId()));
        following.forEach(u -> statIds.add(u.getId()));

        Map<Long, UserStats> stats = bulkStatsForUserIds(statIds, user);
        Map<Long, UserProfile> profiles = profileRepo.findAllByUserIdIn(statIds).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p, (a, b) -> a));
        profiles.put(user.getId(), profile);

        return dashboardFor(user, profile, stats, profiles, ownMatch.orElse(null),
                mentorMatches, following, socialCandidates);
    }

    @Transactional
    public AccountabilityDashboardResponse updateProfile(Long userId, ProfileRequest req) {
        User user = requireUser(userId);
        UserProfile profile = ensureProfile(user);
        profile.setDisplayName(req.displayName().trim());
        profile.setAvatarUrl(blankToNull(req.avatarUrl()));
        profile.setTimezone(req.timezone().trim());
        profile.setLanguage(req.language().trim().toUpperCase());
        profile.setGoals(req.goals().trim());
        profileRepo.save(profile);
        return dashboard(userId);
    }

    @Transactional
    public AccountabilityDashboardResponse assignMentor(Long userId) {
        User mentee = requireUser(userId);
        UserProfile menteeProfile = ensureProfile(mentee);
        UserStats menteeStats = statsFor(mentee);
        if (!canFindMentor(menteeStats)) {
            throw new IllegalStateException(mentorMatchingWaitMessage(menteeStats));
        }

        Optional<MentorMatch> existing = matchRepo.findFirstByMenteeAndStatusInOrderByCreatedAtDesc(mentee, LIVE_MATCH_STATUSES);
        Set<Long> excludedMentorIds = new HashSet<>();
        if (existing.isPresent()) {
            MentorMatch current = existing.get();
            if (isMentorLocked(current)) {
                throw new IllegalStateException("This mentor match is locked until " + mentorLockedUntil(current) + ".");
            }
            excludedMentorIds.add(current.getMentor().getId());
        }

        MentorCandidateScore best = bestMentorFor(mentee, menteeProfile, excludedMentorIds)
                .orElseThrow(() -> new IllegalStateException("No eligible mentors are available yet"));

        createMentorMatch(
                mentee,
                menteeProfile,
                best,
                existing.orElse(null),
                existing.isPresent()
                        ? "Manual mentor change requested after the previous lock window."
                        : "Manual accountability match requested.",
                true
        );

        // Re-fetch via the unified dashboard path so the response reflects all
        // side effects (new match, ended old match, freshly-published SSE state)
        // with bulk-loaded stats — matches the pattern used by releaseMatch /
        // useStreakFreeze / requestFriend.
        return dashboard(userId);
    }

    @Scheduled(cron = "0 15 9 * * *")
    @Transactional
    public void reviewMentorships() {
        List<MentorMatch> activeMatches = matchRepo.findAllByStatus(MentorMatchStatus.ACTIVE);
        for (MentorMatch match : activeMatches) {
            reviewMentorship(match);
        }
    }

    /**
     * Sends one windowed check-in as the AI mentor on the given match.
     * Called by {@link MentorCheckInScheduler} once per window per day, after
     * it has already verified the mentee has incomplete habits and this
     * window hasn't fired yet today. Persists the generated message through
     * the regular mentor-chat path so the client renders it via existing SSE.
     */
    @Transactional
    public void aiScheduledCheckIn(Long matchId, MentorAI.CheckInWindow window) {
        if (!mentorAI.isConfigured()) return;
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null || !match.isAiMentor() || match.getStatus() != MentorMatchStatus.ACTIVE) return;

        User mentee = match.getMentee();
        User aiMentor = match.getMentor();
        UserProfile profile = ensureProfile(mentee);
        MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(profile);
        String memory = profile.getMentorMemory();

        String text = mentorAI.generatePeriodicCheckIn(buildMentorContext(mentee, profile), personality, memory, window);
        if (text.isBlank()) return;
        postAiMentorMessage(match, aiMentor, text);
    }

    /**
     * Sends an in-character "you look overloaded — drop one habit" message.
     * Called by {@link MentorOverloadDetector} for users flagged as stretched
     * thin. Assessment is pre-computed in code (not by the AI) — the AI only
     * drafts the copy in the user's current personality.
     */
    @Transactional
    public void aiOverloadNudge(Long matchId, MentorAI.OverloadAssessment assessment) {
        if (!mentorAI.isConfigured() || !assessment.overloaded()) return;
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null || !match.isAiMentor() || match.getStatus() != MentorMatchStatus.ACTIVE) return;

        User mentee = match.getMentee();
        User aiMentor = match.getMentor();
        UserProfile profile = ensureProfile(mentee);
        MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(profile);
        String memory = profile.getMentorMemory();

        String text = mentorAI.generateOverloadNudge(buildMentorContext(mentee, profile), personality, memory, assessment);
        if (text.isBlank()) return;
        postAiMentorMessage(match, aiMentor, text);
    }

    /**
     * Updates the mentee's rolling memory note from the recent chat history.
     * Invoked nightly by {@link MentorMemoryDistiller} so chat latency is
     * never blocked on the memory call. Lives here so it shares the same
     * transactional context as the rest of the AI-mentor surface.
     */
    @Transactional
    public void refreshAiMentorMemory(Long matchId) {
        if (!mentorAI.isConfigured()) return;
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null || !match.isAiMentor()) return;

        User mentee = match.getMentee();
        UserProfile profile = ensureProfile(mentee);
        List<MentorAI.ChatTurn> history = recentMentorHistoryAsTurns(match, match.getMentor());
        if (history.isEmpty()) return;

        String updated = mentorAI.distillMemory(buildMentorContext(mentee, profile), history, profile.getMentorMemory());
        if (updated == null || updated.isBlank()) return;
        profile.setMentorMemory(updated);
        profileRepo.save(profile);
    }

    private void postAiMentorMessage(MentorMatch match, User aiMentor, String text) {
        MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(aiMentor)
                .message(truncateForChat(text))
                .nudge(true)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(match.getId(), "message.created", toMessage(saved));
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
        pushNudgeToReceiver(aiMentor, match, saved.getMessage());
    }

    /**
     * Legacy hourly AI-mentor pass — retained as a safety net so users with
     * AI matches still get at least one check-in per day even if the window
     * scheduler misses them (e.g. server was down during all 4 windows).
     * The 18-hour cooldown keeps it from piling on top of the new windowed
     * messages.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void aiMentorCheckIns() {
        if (!mentorAI.isConfigured()) return;
        Instant cutoff = Instant.now().minus(18, ChronoUnit.HOURS);
        List<MentorMatch> active = matchRepo.findAllByStatus(MentorMatchStatus.ACTIVE);
        for (MentorMatch match : active) {
            if (!match.isAiMentor()) continue;
            User aiMentor = match.getMentor();
            if (mentorCheckedInSince(match, cutoff)) continue;

            UserProfile profile = ensureProfile(match.getMentee());
            MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(profile);
            String memory = profile.getMentorMemory();
            String text = mentorAI.generatePeriodicCheckIn(
                    buildMentorContext(match.getMentee(), profile),
                    personality,
                    memory,
                    MentorAI.CheckInWindow.EVENING
            );
            if (text.isBlank()) continue;

            MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                    .match(match)
                    .sender(aiMentor)
                    .message(truncateForChat(text))
                    .nudge(true)
                    .createdAt(Instant.now())
                    .build());
            streamService.publishToMatch(match.getId(), "message.created", toMessage(saved));
            streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
            pushNudgeToReceiver(aiMentor, match, saved.getMessage());
        }
    }

    private void tryReplaceAiWithHuman(MentorMatch aiMatch) {
        // AI mentor is permanent under the current product configuration.
        // The hand-off to a human mentor is disabled so the mentor character
        // stays AI indefinitely. Restore the commented-out body to re-enable
        // the upgrade path when real-person mentors come back.
        aiMatch.setLastReviewedAt(Instant.now());
        matchRepo.save(aiMatch);
        /*
        User mentee = aiMatch.getMentee();
        UserProfile menteeProfile = ensureProfile(mentee);
        Optional<MentorCandidateScore> human = bestMentorFor(mentee, menteeProfile, Set.of());
        if (human.isEmpty()) {
            aiMatch.setLastReviewedAt(Instant.now());
            matchRepo.save(aiMatch);
            return;
        }
        createMentorMatch(
                mentee,
                menteeProfile,
                human.get(),
                aiMatch,
                "A human mentor is now available — handing off from your AI mentor.",
                true
        );
        */
    }

    private Optional<MentorMatch> autoAssignMentor(User mentee, UserProfile menteeProfile) {
        // Human auto-matching is disabled — the AI mentor is the product
        // default. Every new user is assigned the AI mentor on first
        // dashboard load so the mentor character is populated immediately.
        // To re-enable human mentor matching (e.g., if a real-person mentor
        // ships again), uncomment the bestMentorFor block below.
        /*
        Optional<MentorCandidateScore> human = bestMentorFor(mentee, menteeProfile, Set.of());
        if (human.isPresent()) {
            return Optional.of(createMentorMatch(
                    mentee,
                    menteeProfile,
                    human.get(),
                    null,
                    "Automatic accountability match after you started tracking habits.",
                    true
            ));
        }
        */
        return aiMentorUser().map(ai -> createAiMentorMatch(
                mentee,
                menteeProfile,
                ai,
                null,
                "Rung's AI mentor is here to keep you on track with data-driven tips."
        ));
    }

    private MentorMatch createMentorMatch(
            User mentee,
            UserProfile menteeProfile,
            MentorCandidateScore best,
            MentorMatch previousMatch,
            String changeReason,
            boolean sendEmails
    ) {
        User oldMentor = previousMatch == null ? null : previousMatch.getMentor();
        if (previousMatch != null) {
            endMatch(previousMatch, changeReason);
            streamService.publishToMatch(previousMatch.getId(), "match.updated", toMatch(previousMatch));
        }

        MentorMatch match = MentorMatch.builder()
                .mentor(best.user())
                .mentee(mentee)
                .status(MentorMatchStatus.ACTIVE)
                .matchScore(best.score())
                .matchReasons(String.join(" | ", best.reasons()))
                .createdAt(Instant.now())
                .lastReviewedAt(Instant.now())
                .aiMentor(false)
                .build();
        matchRepo.save(match);

        MentorshipMessage welcomeMessage = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(best.user())
                .message("I am here with you. What is the smallest habit you can finish today?")
                .nudge(true)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(match.getId(), "message.created", toMessage(welcomeMessage));
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));

        if (sendEmails) {
            sendMatchEmails(match, menteeProfile, oldMentor, changeReason);
        }
        return match;
    }

    private MentorMatch createAiMentorMatch(
            User mentee,
            UserProfile menteeProfile,
            User aiMentor,
            MentorMatch previousMatch,
            String changeReason
    ) {
        if (previousMatch != null) {
            endMatch(previousMatch, changeReason);
            streamService.publishToMatch(previousMatch.getId(), "match.updated", toMatch(previousMatch));
        }

        List<String> reasons = List.of(
                "AI fallback while a human match is unavailable",
                "Personalised to your habits and weekly trend",
                "Daily check-ins and tiny next-step suggestions"
        );

        MentorMatch match = MentorMatch.builder()
                .mentor(aiMentor)
                .mentee(mentee)
                .status(MentorMatchStatus.ACTIVE)
                .matchScore(0)
                .matchReasons(String.join(" | ", reasons))
                .createdAt(Instant.now())
                .lastReviewedAt(Instant.now())
                .aiMentor(true)
                .build();
        matchRepo.save(match);

        // Persist the static fallback welcome up-front so the dashboard
        // returns immediately. The personalised AI rewrite runs after
        // commit (AiMentorWelcomeRequestedEvent → AiMentorReplyListener).
        // This keeps the first-dashboard load off the Anthropic critical path.
        MentorshipMessage welcomeMessage = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(aiMentor)
                .message(truncateForChat(AI_MENTOR_FALLBACK_WELCOME))
                .nudge(true)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(match.getId(), "message.created", toMessage(welcomeMessage));
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));

        if (mentorAI.isConfigured()) {
            eventPublisher.publishEvent(
                    new AiMentorWelcomeRequestedEvent(match.getId(), welcomeMessage.getId())
            );
        }
        return match;
    }

    /// Async upgrade for the AI mentor welcome row written by
    /// {@link #createAiMentorMatch}. Invoked by the AFTER_COMMIT listener so
    /// the original transaction (and the dashboard response) is not blocked
    /// on the Anthropic round-trip. Silently exits when the API key is unset
    /// or the message has already been replaced.
    @Transactional
    public void regenerateAiMentorWelcome(Long matchId, Long welcomeMessageId) {
        if (!mentorAI.isConfigured()) return;
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null || !match.isAiMentor()) return;
        MentorshipMessage message = messageRepo.findById(welcomeMessageId).orElse(null);
        if (message == null) return;

        UserProfile menteeProfile = ensureProfile(match.getMentee());
        MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(menteeProfile);
        String memory = menteeProfile.getMentorMemory();
        String text = mentorAI.generateMentorWelcome(buildMentorContext(match.getMentee(), menteeProfile), personality, memory);
        if (text.isBlank()) return;

        message.setMessage(truncateForChat(text));
        messageRepo.save(message);
        // `match.updated` triggers a dashboard refresh on the iOS/macOS
        // client, which surfaces the rewritten welcome text on the next
        // payload. The client dedupes by message ID, so re-emitting
        // `message.created` would be silently ignored.
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
    }

    private void reviewMentorship(MentorMatch match) {
        if (match.isAiMentor()) {
            // AI mentors don't get rated or reassigned; the daily AI check-in
            // scheduler handles outreach. Try promoting the mentee onto a
            // human mentor as soon as one becomes available.
            tryReplaceAiWithHuman(match);
            return;
        }
        User mentor = match.getMentor();
        User mentee = match.getMentee();
        UserStats menteeStats = statsFor(mentee);
        boolean mentorCheckedRecently = mentorCheckedInSince(match, Instant.now().minus(MENTOR_INACTIVE_DAYS, ChronoUnit.DAYS));

        if (menteeStats.weeklyConsistencyPercent() >= 82 && mentorCheckedRecently) {
            adjustMentorRating(mentor, 2);
            match.setLastReviewedAt(Instant.now());
            matchRepo.save(match);
            return;
        }

        boolean menteeNeedsMoreSupport = menteeStats.weeklyConsistencyPercent() < 58 || menteeStats.missedToday() > 0;
        boolean mentorInactive = !mentorCheckedRecently && Instant.now().isAfter(match.getCreatedAt().plus(1, ChronoUnit.DAYS));
        if (!mentorInactive || !menteeNeedsMoreSupport) {
            match.setLastReviewedAt(Instant.now());
            matchRepo.save(match);
            return;
        }

        String reason = "The mentee needs more active accountability and the current mentor has not checked in for "
                + MENTOR_INACTIVE_DAYS + " days.";
        adjustMentorRating(mentor, -6);

        Set<Long> excluded = new HashSet<>();
        excluded.add(mentor.getId());
        MentorCandidateScore replacement = bestMentorFor(mentee, ensureProfile(mentee), excluded).orElse(null);
        if (replacement == null) {
            match.setLastReviewedAt(Instant.now());
            matchRepo.save(match);
            return;
        }

        createMentorMatch(mentee, ensureProfile(mentee), replacement, match, reason, true);
    }

    private boolean mentorCheckedInSince(MentorMatch match, Instant after) {
        return messageRepo.existsByMatchAndSenderAndCreatedAtAfter(match, match.getMentor(), after);
    }

    private void adjustMentorRating(User mentor, int delta) {
        UserProfile profile = ensureProfile(mentor);
        profile.setMentorRating(clamp(profile.getMentorRating() + delta, MENTOR_RATING_MIN, MENTOR_RATING_MAX));
        profileRepo.save(profile);
    }

    private void sendMatchEmails(MentorMatch match, UserProfile menteeProfile, User oldMentor, String reason) {
        User mentee = match.getMentee();
        User mentor = match.getMentor();
        UserProfile mentorProfile = ensureProfile(mentor);
        // Treat AI mentor as "no old mentor" — it has no inbox and shouldn't
        // receive the reassignment email chain.
        if (oldMentor != null && isAiMentor(oldMentor)) {
            oldMentor = null;
        }

        if (oldMentor == null) {
            emailService.sendMentorAssignedToMentee(
                    mentee.getEmail(),
                    menteeProfile.getDisplayName(),
                    mentorProfile.getDisplayName(),
                    reason
            );
            emailService.sendMenteeAssignedToMentor(
                    mentor.getEmail(),
                    mentorProfile.getDisplayName(),
                    menteeProfile.getDisplayName(),
                    reason
            );
            return;
        }

        UserProfile oldMentorProfile = ensureProfile(oldMentor);
        emailService.sendMentorChangedToMentee(
                mentee.getEmail(),
                menteeProfile.getDisplayName(),
                oldMentorProfile.getDisplayName(),
                mentorProfile.getDisplayName(),
                reason
        );
        emailService.sendMenteeReassignedFromMentor(
                oldMentor.getEmail(),
                oldMentorProfile.getDisplayName(),
                menteeProfile.getDisplayName(),
                reason,
                oldMentorProfile.getMentorRating()
        );
        emailService.sendReassignedMenteeToNewMentor(
                mentor.getEmail(),
                mentorProfile.getDisplayName(),
                menteeProfile.getDisplayName(),
                reason
        );
    }

    @Transactional
    public AccountabilityDashboardResponse releaseMatch(Long userId, Long matchId) {
        User user = requireUser(userId);
        MentorMatch match = requireMatchParticipant(matchId, user);
        if (isMentorLocked(match)) {
            throw new IllegalStateException("This mentor match is locked until " + mentorLockedUntil(match) + ".");
        }

        endMatch(match);
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
        return dashboard(userId);
    }

    @Transactional
    public AccountabilityDashboardResponse sendMessage(Long userId, Long matchId, MentorshipMessageRequest req, boolean nudge) {
        User sender = requireUser(userId);
        MentorMatch match = requireMatchParticipant(matchId, sender);
        String text = req.message().trim();
        MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(sender)
                .message(text)
                .nudge(nudge)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(matchId, "message.created", toMessage(saved));
        streamService.publishToMatch(matchId, "match.updated", toMatch(match));

        if (nudge) {
            pushNudgeToReceiver(sender, match, text);
        }

        if (match.isAiMentor() && sender.getId().equals(match.getMentee().getId())) {
            // Gate the AI call BEFORE publishing the event so a runaway client
            // can't queue thousands of pending Anthropic round-trips by
            // looping POSTs faster than the listener can drain them. Throws
            // 429 if the per-user hourly budget is exhausted.
            aiMentorRateLimiter.checkAiBudget(userId);
            eventPublisher.publishEvent(new MenteeMessageReceivedEvent(match.getId(), text));
        }

        return dashboard(userId);
    }

    @Transactional
    public void generateAndPersistAiMentorReply(Long matchId, String latestMenteeMessage) {
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null) return;
        replyAsAiMentor(match, latestMenteeMessage);
    }

    private void replyAsAiMentor(MentorMatch match, String latestMenteeMessage) {
        if (!mentorAI.isConfigured()) return;

        User mentee = match.getMentee();
        User aiMentor = match.getMentor();
        UserProfile menteeProfile = ensureProfile(mentee);
        MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(menteeProfile);
        String memory = menteeProfile.getMentorMemory();

        List<MentorAI.ChatTurn> history = recentMentorHistoryAsTurns(match, aiMentor);

        String reply = mentorAI.generateMentorReply(
                buildMentorContext(mentee, menteeProfile),
                personality,
                memory,
                history,
                latestMenteeMessage
        );
        if (reply.isBlank()) return;

        MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(aiMentor)
                .message(truncateForChat(reply))
                .nudge(false)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(match.getId(), "message.created", toMessage(saved));
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
        pushNudgeToReceiver(aiMentor, match, saved.getMessage());
    }

    /** Pulls the most recent N messages oldest-first as MentorAI-style turns. */
    private List<MentorAI.ChatTurn> recentMentorHistoryAsTurns(MentorMatch match, User aiMentor) {
        List<MentorshipMessage> recentDesc = messageRepo.findTop20ByMatchOrderByCreatedAtDesc(match);
        int take = Math.min(recentDesc.size(), AI_HISTORY_TURNS);
        List<MentorshipMessage> chronological = new ArrayList<>(recentDesc.subList(0, take));
        Collections.reverse(chronological);
        List<MentorAI.ChatTurn> turns = new ArrayList<>(chronological.size());
        for (MentorshipMessage msg : chronological) {
            String role = msg.getSender().getId().equals(aiMentor.getId()) ? "assistant" : "user";
            turns.add(new MentorAI.ChatTurn(role, msg.getMessage()));
        }
        return turns;
    }

    private String truncateForChat(String text) {
        String trimmed = text.strip();
        if (trimmed.length() <= AI_REPLY_MAX_CHARS) return trimmed;
        return trimmed.substring(0, AI_REPLY_MAX_CHARS - 1) + "…";
    }

    private Optional<User> aiMentorUser() {
        return userRepo.findByEmail(AI_MENTOR_EMAIL);
    }

    private MentorAI.MentorContext buildMentorContext(User mentee, UserProfile menteeProfile) {
        UserStats stats = statsFor(mentee);
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(mentee, HabitEntryType.HABIT);
        List<String> habitNames = habits.stream().map(Habit::getTitle).toList();
        return new MentorAI.MentorContext(
                menteeProfile.getDisplayName(),
                menteeProfile.getTimezone(),
                menteeProfile.getLanguage(),
                menteeProfile.getGoals(),
                stats.totalHabits(),
                habitNames,
                stats.weeklyConsistencyPercent(),
                stats.recentPerfectDays(),
                stats.bestStreak(),
                stats.historyDays(),
                stats.doneToday(),
                stats.missedToday(),
                buildHabitTimingSummary(mentee),
                stats.verifiedScore(),
                buildWeeklyTargetSummary(habits)
        );
    }

    /// Builds the weekly-target progress block surfaced to the AI mentor
    /// so it can call out specific frequency-habit shortfalls ("Gym: 2/5
    /// this week, 2 rest days already used — Sunday is the last shot").
    /// Empty string when the user has no frequency-based habits.
    private String buildWeeklyTargetSummary(List<Habit> habits) {
        List<Habit> frequency = habits.stream()
                .filter(h -> h.getWeeklyTarget() != null && h.getWeeklyTarget() > 0)
                .toList();
        if (frequency.isEmpty()) return "";

        Set<String> weekKeys = isoWeekDateKeys(LocalDate.now());
        List<HabitCheck> checks = checkRepo.findAllByHabitIn(frequency);
        Map<Long, Integer> completionsByHabit = new HashMap<>();
        for (HabitCheck c : checks) {
            if (!c.isDone()) continue;
            if (!weekKeys.contains(c.getDateKey())) continue;
            completionsByHabit.merge(c.getHabit().getId(), 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        for (Habit h : frequency) {
            int done = completionsByHabit.getOrDefault(h.getId(), 0);
            int target = h.getWeeklyTarget();
            sb.append("- ")
              .append(h.getTitle())
              .append(": ")
              .append(done)
              .append("/")
              .append(target)
              .append(" this week\n");
        }
        return sb.toString().trim();
    }

    /// One-per-line habit timing description feeding the AI mentor prompt, so
    /// suggestions can reference the user's actual hour-of-day patterns (e.g.
    /// "Morning run — mornings (~7am, 12 samples)"). Habits with fewer than
    /// 3 timestamped check-ins are skipped so the AI doesn't over-index on
    /// thin data.
    private String buildHabitTimingSummary(User mentee) {
        List<AccountabilityDashboardResponse.HabitTimeCluster> clusters = habitClustersFor(mentee);
        if (clusters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AccountabilityDashboardResponse.HabitTimeCluster c : clusters) {
            if (c.sampleSize() < 3) continue;
            String slot = switch (c.timeSlot()) {
                case "MORNING"   -> "mornings";
                case "AFTERNOON" -> "afternoons";
                case "EVENING"   -> "evenings";
                case "NIGHT"     -> "nights";
                case "MIXED"     -> "varies across the day";
                default          -> "unknown window";
            };
            if ("MIXED".equals(c.timeSlot())) {
                sb.append("- ").append(c.habitTitle()).append(" — ").append(slot)
                  .append(" (").append(c.sampleSize()).append(" samples)\n");
            } else {
                int hour12 = c.avgHourOfDay() % 12 == 0 ? 12 : c.avgHourOfDay() % 12;
                String suffix = c.avgHourOfDay() < 12 ? "am" : "pm";
                sb.append("- ").append(c.habitTitle()).append(" — ").append(slot)
                  .append(" (~").append(hour12).append(suffix).append(", ")
                  .append(c.sampleSize()).append(" samples)\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Have the AI mentor hold the mentee accountable for today's misses.
     * Generates a short AI message, saves it as a mentor chat message,
     * broadcasts over SSE, and pushes it to the mentee via APNs.
     * Called by the nightly scheduler for every AI mentor match where the
     * mentee still has missed habits. Falls back to a static line if
     * the Anthropic key is unset or the call fails.
     */
    @Transactional
    public void aiNudgeMenteeForMissedHabits(Long matchId, int missedToday) {
        MentorMatch match = matchRepo.findById(matchId).orElse(null);
        if (match == null || !match.isAiMentor()) return;
        User mentee = match.getMentee();
        User aiMentor = match.getMentor();
        UserProfile menteeProfile = ensureProfile(mentee);
        MentorAI.MentorPersonality personality = personalityRotator.currentPersonalityFor(menteeProfile);
        String memory = menteeProfile.getMentorMemory();

        String text = mentorAI.generatePeriodicCheckIn(
                buildMentorContext(mentee, menteeProfile),
                personality,
                memory,
                MentorAI.CheckInWindow.NIGHT
        );
        if (text.isBlank()) {
            text = "You've missed " + missedToday + " habit" + (missedToday == 1 ? "" : "s")
                    + " today. Pick the smallest one and finish it in the next 15 minutes.";
        }

        MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(aiMentor)
                .message(truncateForChat(text))
                .nudge(true)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(match.getId(), "message.created", toMessage(saved));
        streamService.publishToMatch(match.getId(), "match.updated", toMatch(match));
        pushNudgeToReceiver(aiMentor, match, saved.getMessage());
    }

    /** Fire an APNs push to every registered device of the message recipient. */
    private void pushNudgeToReceiver(User sender, MentorMatch match, String message) {
        User receiver = sender.getId().equals(match.getMentor().getId())
                ? match.getMentee()
                : match.getMentor();
        String senderName = profileRepo.findByUser(sender)
                .map(p -> p.getDisplayName())
                .orElse("Your mentor");
        deviceTokenService.tokensForUser(receiver).forEach(dt ->
                apnsService.sendNudge(dt.getToken(), dt.getPlatform(), senderName, message)
        );
    }

    /// No {@code @Transactional} — the SSE emitter is held open for up to
    /// 30 minutes by AccountabilityStreamService, and a tx around it would
    /// pin a HikariCP connection for the full lifetime of the stream.
    /// With even a handful of connected devices the pool is exhausted and
    /// every other DB-bound request stalls on connection-timeout. The two
    /// repository lookups we do here are short reads — Hibernate auto-commit
    /// at the JDBC layer is fine.
    public SseEmitter streamMatch(Long userId, Long matchId, String lastEventId) {
        User user = requireUser(userId);
        MentorMatch match = requireMatchParticipant(matchId, user);
        return streamService.subscribe(match.getId(), userId, lastEventId);
    }

    @Transactional
    public void markMatchRead(Long userId, Long matchId) {
        User user = requireUser(userId);
        MentorMatch match = requireMatchParticipant(matchId, user);
        streamService.publishToMatch(match.getId(), "message.read", new MessageReadEvent(match.getId(), user.getId(), Instant.now()));
    }

    @Transactional
    public AccountabilityDashboardResponse sendNudge(Long userId, Long matchId) {
        return sendMessage(
                userId,
                matchId,
                new MentorshipMessageRequest("Gentle check-in: what is one tiny habit you can complete next?"),
                true
        );
    }

    /** Returns the number of habits the user missed today. Used by scheduler. */
    @Transactional(readOnly = true)
    public int missedTodayFor(User user) {
        return statsFor(user).missedToday();
    }

    @Transactional
    public AccountabilityDashboardResponse useStreakFreeze(Long userId, String dateKey) {
        User user = requireUser(userId);
        boolean used = streakFreezeService.useFreeze(user, dateKey);
        if (!used) {
            throw new IllegalStateException("No streak freeze tokens available.");
        }
        return dashboard(userId);
    }

    @Transactional
    public AccountabilityDashboardResponse undoStreakFreeze(Long userId) {
        User user = requireUser(userId);
        boolean reverted = streakFreezeService.undoLastFreeze(user);
        if (!reverted) {
            throw new IllegalStateException("No recent freeze to undo.");
        }
        return dashboard(userId);
    }

    private List<AccountabilityDashboardResponse.SocialPost> feed(int limit) {
        return postRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(this::toSocialPost)
                .toList();
    }

    @Transactional
    public AccountabilityDashboardResponse requestFriend(Long userId, Long friendUserId) {
        User requester = requireUser(userId);
        User addressee = requireUser(friendUserId);
        if (requester.getId().equals(addressee.getId())) {
            throw new IllegalArgumentException("You cannot follow yourself");
        }

        Optional<FriendConnection> existing = friendRepo.findByRequesterAndAddressee(requester, addressee);
        if (existing.isEmpty()) {
            Instant now = Instant.now();
            friendRepo.save(FriendConnection.builder()
                    .requester(requester)
                    .addressee(addressee)
                    .status(FriendConnectionStatus.ACCEPTED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } else {
            FriendConnection connection = existing.get();
            connection.setStatus(FriendConnectionStatus.ACCEPTED);
            connection.setUpdatedAt(Instant.now());
            friendRepo.save(connection);
        }

        return dashboard(userId);
    }

    public List<AccountabilityDashboardResponse.FriendSummary> searchFriends(Long userId, String query) {
        User user = requireUser(userId);
        UserProfile profile = ensureProfile(user);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        // SQL filter — drops the viewer and the AI mentor without a full scan.
        List<User> candidatePool = userRepo.findAllSocialCandidates(user.getId(), AI_MENTOR_USERNAME);
        Set<Long> statIds = new HashSet<>();
        statIds.add(user.getId());
        candidatePool.forEach(c -> statIds.add(c.getId()));
        Map<Long, UserStats> statsByUserId = bulkStatsForUserIds(statIds, user);
        Map<Long, UserProfile> profilesByUserId = profileRepo.findAllByUserIdIn(statIds).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p, (a, b) -> a));
        profilesByUserId.put(user.getId(), profile);

        if (normalizedQuery.isBlank()) {
            return suggestedFriends(user, profile, statsByUserId, profilesByUserId, candidatePool).stream()
                    .map(candidate -> toFriendSummary(candidate.user(), statsByUserId, profilesByUserId))
                    .limit(10)
                    .toList();
        }

        Set<Long> followingIds = followingIds(user);
        return candidatePool.stream()
                .filter(candidate -> !followingIds.contains(candidate.getId()))
                .filter(candidate -> isDiscoverableSocialCandidate(candidate, statsByUserId))
                .filter(candidate -> matchesSearch(candidate, profilesByUserId, normalizedQuery))
                .map(candidate -> toFriendSummary(candidate, statsByUserId, profilesByUserId))
                .sorted(Comparator
                        .comparingInt(AccountabilityDashboardResponse.FriendSummary::weeklyConsistencyPercent)
                        .reversed()
                        .thenComparing(AccountabilityDashboardResponse.FriendSummary::displayName, String.CASE_INSENSITIVE_ORDER))
                .limit(20)
                .toList();
    }

    private AccountabilityDashboardResponse dashboardFor(
            User user,
            UserProfile profile,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId,
            MentorMatch ownMatch,
            List<MentorMatch> mentorMatches,
            List<User> following,
            List<User> socialCandidates
    ) {
        UserStats viewerStats = statsByUserId.get(user.getId());
        List<MentorshipMessage> messages = ownMatch == null
                ? List.of()
                : messageRepo.findTop20ByMatchOrderByCreatedAtDesc(ownMatch);
        return new AccountabilityDashboardResponse(
                toProfile(user, profile),
                toLevel(viewerStats),
                ownMatch == null ? null : toMatch(ownMatch, statsByUserId, profilesByUserId),
                new AccountabilityDashboardResponse.MenteeDashboard(
                        mentorTip(viewerStats),
                        viewerStats.missedToday(),
                        viewerStats.accountabilityScore(),
                        messages.stream().map(this::toMessage).toList()
                ),
                new AccountabilityDashboardResponse.MentorDashboard(
                        mentorMatches.size(),
                        mentorMatches.stream()
                                .map(m -> toMenteeSummary(m, statsByUserId, profilesByUserId))
                                .toList()
                ),
                mentorshipStatusFor(viewerStats, ownMatch),
                new AccountabilityDashboardResponse.Rewards(
                        viewerStats.xp(),
                        badgesFor(viewerStats),
                        viewerStats.checksToday(),
                        RewardService.DAILY_GRANT_CAP,
                        viewerStats.rewardEligible(),
                        streakFreezeService.availableFreezes(user),
                        streakFreezeService.frozenDates(user)
                ),
                weeklyChallengeFor(user, viewerStats),
                socialDashboardFor(user, profile, statsByUserId, profilesByUserId, following, socialCandidates),
                feed(20),
                notificationsFor(viewerStats, ownMatch, mentorMatches),
                habitClustersFor(user)
        );
    }

    private Optional<MentorCandidateScore> bestMentorFor(User mentee, UserProfile menteeProfile, Set<Long> excludedUserIds) {
        // SQL-level filter drops the mentee and AI mentor without a full scan.
        return userRepo.findAllSocialCandidates(mentee.getId(), AI_MENTOR_USERNAME).stream()
                .filter(candidate -> !excludedUserIds.contains(candidate.getId()))
                .map(candidate -> scoreCandidate(candidate, menteeProfile))
                .flatMap(Optional::stream)
                .max(Comparator.comparingInt(MentorCandidateScore::score));
    }

    private boolean isAiMentor(User user) {
        if (user == null) return false;
        if (AI_MENTOR_EMAIL.equalsIgnoreCase(user.getEmail())) return true;
        return AI_MENTOR_USERNAME.equalsIgnoreCase(user.getUsername());
    }

    private Optional<MentorCandidateScore> scoreCandidate(User candidate, UserProfile menteeProfile) {
        UserProfile candidateProfile = profileRepo.findByUser(candidate).orElse(null);
        if (candidateProfile == null) {
            return Optional.empty();
        }

        UserStats stats = statsFor(candidate);
        int consistencyScore = (int) Math.round(stats.weeklyConsistency() * 45);
        int goalScore = (int) Math.round(goalOverlap(menteeProfile.getGoals(), candidateProfile.getGoals()) * 25);
        int timezoneScore = timezoneScore(menteeProfile.getTimezone(), candidateProfile.getTimezone());
        int languageScore = menteeProfile.getLanguage().equalsIgnoreCase(candidateProfile.getLanguage()) ? 15 : 0;
        int friendScore = isFollowing(menteeProfile.getUser(), candidate) ? 20 : 0;
        int mentorRatingScore = Math.max(0, candidateProfile.getMentorRating() - MENTOR_RATING_DEFAULT);
        int loadPenalty = (int) Math.min(matchRepo.countByMentorAndStatus(candidate, MentorMatchStatus.ACTIVE) * 8, 24);
        int score = Math.max(0, consistencyScore + goalScore + timezoneScore + languageScore + friendScore + mentorRatingScore - loadPenalty);

        List<String> reasons = new ArrayList<>();
        reasons.add(stats.weeklyConsistencyPercent() + "% consistency");
        reasons.add(candidateProfile.getMentorRating() + " mentor rating");
        if (friendScore > 0) reasons.add("already in your circle");
        if (goalScore > 0) reasons.add("similar goals");
        if (timezoneScore > 0) reasons.add("timezone fit");
        if (languageScore > 0) reasons.add("language match");
        if (loadPenalty == 0) reasons.add("mentor has capacity");

        return Optional.of(new MentorCandidateScore(candidate, stats, score, reasons));
    }

    private double goalOverlap(String a, String b) {
        Set<String> left = goalTokens(a);
        Set<String> right = goalTokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / (double) union.size();
    }

    private Set<String> goalTokens(String goals) {
        return Arrays.stream(goals.toLowerCase().split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
    }

    private int timezoneScore(String menteeTimezone, String mentorTimezone) {
        if (menteeTimezone.equalsIgnoreCase(mentorTimezone)) return 15;
        String menteeRegion = menteeTimezone.split("/")[0];
        String mentorRegion = mentorTimezone.split("/")[0];
        return menteeRegion.equalsIgnoreCase(mentorRegion) ? 8 : 0;
    }

    private UserStats statsFor(User user) {
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, HabitEntryType.HABIT);
        List<HabitCheck> checks = habits.isEmpty() ? List.of() : checkRepo.findAllByHabitIn(habits);
        return computeStats(habits, checks, rewardsFor(user));
    }

    private RewardSnapshot rewardsFor(User user) {
        return new RewardSnapshot(
                rewardService.totalXp(user),
                rewardService.checksGrantedToday(user),
                rewardService.isRewardEligible(user)
        );
    }

    /// Pure transformation — no DB calls. Shared by the per-user `statsFor` path
    /// and the bulk `bulkStatsForUserIds` path so both produce identical output.
    private UserStats computeStats(List<Habit> habits, List<HabitCheck> checks, RewardSnapshot rewards) {
        Set<String> doneKeys = checks.stream()
                .filter(HabitCheck::isDone)
                .map(HabitCheck::getDateKey)
                .collect(Collectors.toSet());

        String todayKey = LocalDate.now().toString();
        int totalHabits = habits.size();
        int doneToday = (int) checks.stream()
                .filter(HabitCheck::isDone)
                .filter(check -> todayKey.equals(check.getDateKey()))
                .count();
        int totalChecks = (int) checks.stream().filter(HabitCheck::isDone).count();
        double progressToday = totalHabits == 0 ? 0 : (double) doneToday / totalHabits;
        double weeklyConsistency = weeklyConsistency(habits, checks);
        int weeklyConsistencyPercent = (int) Math.round(weeklyConsistency * 100);
        int perfectDays = perfectDays(habits, checks).size();
        int bestStreak = bestStreak(doneKeys);
        int historyDays = historyDays(doneKeys);
        boolean hasSevenDays = historyDays >= 7 || doneKeys.size() >= 7;
        boolean mentorEligible = hasSevenDays && totalHabits > 0 && weeklyConsistency >= 0.82;
        boolean needsMentor = hasSevenDays && totalHabits > 0 && weeklyConsistency < 0.58;
        String level = levelName(totalChecks, weeklyConsistency, bestStreak, hasSevenDays);
        int accountabilityScore = Math.min(100, (int) Math.round(weeklyConsistency * 70 + progressToday * 30));
        int recentPerfectDays = recentPerfectDays(habits, checks);
        int yearPerfectDays = yearPerfectDays(habits, checks);
        int verifiedScore = computeVerifiedScore(checks);

        return new UserStats(
                totalHabits,
                doneToday,
                Math.max(totalHabits - doneToday, 0),
                totalChecks,
                weeklyConsistency,
                weeklyConsistencyPercent,
                accountabilityScore,
                level,
                mentorEligible,
                needsMentor,
                rewards.xp(),
                perfectDays,
                bestStreak,
                recentPerfectDays,
                historyDays,
                rewards.checksToday(),
                rewards.rewardEligible(),
                yearPerfectDays,
                verifiedScore
        );
    }

    /// Sums the user's checks for the current ISO week, weighted by the
    /// `verification_tier` captured when the check was recorded:
    /// auto × 10, partial × 5, selfReport × 1, missing tier × 0. This is
    /// the anti-cheat number that powers the leaderboard's `verifiedScore`
    /// — a self-reported habit can't out-earn a HealthKit-verified one.
    private int computeVerifiedScore(List<HabitCheck> checks) {
        if (checks.isEmpty()) return 0;
        Set<String> weekKeys = isoWeekDateKeys(LocalDate.now());
        int score = 0;
        for (HabitCheck check : checks) {
            if (!check.isDone()) continue;
            if (!weekKeys.contains(check.getDateKey())) continue;
            String tier = check.getVerificationTier();
            if (tier == null) continue;
            score += switch (tier) {
                case "auto" -> 10;
                case "partial" -> 5;
                case "selfReport" -> 1;
                default -> 0;
            };
        }
        return score;
    }

    /// All seven date keys (Mon..Sun) for the ISO week containing
    /// `referenceDate`, formatted as "yyyy-MM-dd" to match `HabitCheck`.
    private Set<String> isoWeekDateKeys(LocalDate referenceDate) {
        WeekFields weekFields = WeekFields.ISO;
        LocalDate monday = referenceDate.with(weekFields.dayOfWeek(), 1);
        Set<String> keys = new HashSet<>();
        for (int offset = 0; offset < 7; offset++) {
            keys.add(monday.plusDays(offset).toString());
        }
        return keys;
    }

    /// Single-shot bulk loader — issues exactly two queries (habits + checks) for
    /// the entire `userIds` set, groups them in memory, and returns the full
    /// stats map. Reward fields are populated only for `viewer`; everyone else
    /// gets `EMPTY_REWARDS` because friends/suggestions never surface XP.
    /// Replaces the per-user N+1 pattern that `dashboardFor` used to trigger.
    private Map<Long, UserStats> bulkStatsForUserIds(Set<Long> userIds, User viewer) {
        if (userIds.isEmpty()) return Map.of();

        List<Habit> allHabits = habitRepo.findAllByUserIdInAndEntryType(userIds, HabitEntryType.HABIT);
        List<HabitCheck> allChecks = allHabits.isEmpty()
                ? List.of()
                : checkRepo.findAllByHabitUserIdInAndEntryType(userIds, HabitEntryType.HABIT);

        Map<Long, List<Habit>> habitsByUser = allHabits.stream()
                .collect(Collectors.groupingBy(h -> h.getUser().getId()));
        // Cache habit -> userId once so the subsequent stream doesn't initialise
        // a Habit proxy per check (which would defeat the bulk fetch).
        Map<Long, Long> habitToUser = allHabits.stream()
                .collect(Collectors.toMap(Habit::getId, h -> h.getUser().getId()));
        Map<Long, List<HabitCheck>> checksByUser = allChecks.stream()
                .filter(hc -> habitToUser.containsKey(hc.getHabit().getId()))
                .collect(Collectors.groupingBy(hc -> habitToUser.get(hc.getHabit().getId())));

        RewardSnapshot viewerRewards = rewardsFor(viewer);
        Long viewerId = viewer.getId();
        Map<Long, UserStats> result = new HashMap<>(userIds.size());
        for (Long uid : userIds) {
            List<Habit> habits = habitsByUser.getOrDefault(uid, List.of());
            List<HabitCheck> checks = checksByUser.getOrDefault(uid, List.of());
            RewardSnapshot rewards = uid.equals(viewerId) ? viewerRewards : EMPTY_REWARDS;
            result.put(uid, computeStats(habits, checks, rewards));
        }
        return result;
    }

    /// Cache-aware stats lookup. Falls back to a per-user DB hit only when the
    /// bulk loader didn't cover this user (e.g. one-off helpers like
    /// `assignMentor` that don't precompute).
    private UserStats statsFor(User user, Map<Long, UserStats> cache) {
        UserStats cached = cache.get(user.getId());
        return cached != null ? cached : statsFor(user);
    }

    private double weeklyConsistency(List<Habit> habits, List<HabitCheck> checks) {
        if (habits.isEmpty()) return 0;
        Set<String> recentKeys = recentDateKeys(7);
        long completed = checks.stream()
                .filter(HabitCheck::isDone)
                .filter(check -> recentKeys.contains(check.getDateKey()))
                .count();
        return Math.min((double) completed / (double) (habits.size() * recentKeys.size()), 1);
    }

    private Set<String> recentDateKeys(int count) {
        LocalDate today = LocalDate.now();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < count; i++) {
            keys.add(today.minusDays(i).toString());
        }
        return keys;
    }

    private Set<String> perfectDays(List<Habit> habits, List<HabitCheck> checks) {
        if (habits.isEmpty()) return Set.of();
        Map<String, Long> counts = checks.stream()
                .filter(HabitCheck::isDone)
                .collect(Collectors.groupingBy(HabitCheck::getDateKey, Collectors.counting()));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == habits.size())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private int recentPerfectDays(List<Habit> habits, List<HabitCheck> checks) {
        Set<String> recent = recentDateKeys(7);
        return (int) perfectDays(habits, checks).stream()
                .filter(recent::contains)
                .count();
    }

    private int yearPerfectDays(List<Habit> habits, List<HabitCheck> checks) {
        int currentYear = LocalDate.now().getYear();
        return (int) perfectDays(habits, checks).stream()
                .map(LocalDate::parse)
                .filter(date -> date.getYear() == currentYear)
                .count();
    }

    /// Year-to-date perfect days for any user the viewer can see.
    /// Self-lookup is always allowed; otherwise the viewer must follow the
    /// target (the same trust boundary the social dashboard already enforces).
    @Transactional
    public int yearPerfectDays(Long viewerId, Long targetUserId) {
        User viewer = requireUser(viewerId);
        User target = viewerId.equals(targetUserId) ? viewer : requireUser(targetUserId);
        if (!viewerId.equals(targetUserId) && !isFollowing(viewer, target)) {
            throw new IllegalArgumentException("You can only view perfect days for users you follow.");
        }
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(target, HabitEntryType.HABIT);
        List<HabitCheck> checks = habits.isEmpty() ? List.of() : checkRepo.findAllByHabitIn(habits);
        return yearPerfectDays(habits, checks);
    }

    private int bestStreak(Set<String> keys) {
        if (keys.isEmpty()) return 0;
        List<LocalDate> dates = keys.stream().map(LocalDate::parse).sorted().toList();
        int best = 1;
        int current = 1;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i - 1).plusDays(1).equals(dates.get(i))) {
                current++;
            } else {
                current = 1;
            }
            best = Math.max(best, current);
        }
        return best;
    }

    private int historyDays(Set<String> doneKeys) {
        return doneKeys.stream()
                .map(LocalDate::parse)
                .min(Comparator.naturalOrder())
                .map(first -> Math.max((int) (LocalDate.now().toEpochDay() - first.toEpochDay()) + 1, 0))
                .orElse(0);
    }

    private String levelName(int totalChecks, double weeklyConsistency, int bestStreak, boolean hasSevenDays) {
        if (!hasSevenDays) return totalChecks > 8 ? "Rising" : "Beginner";
        if (weeklyConsistency >= 0.92 && bestStreak >= 21) return "Master Mentor";
        if (weeklyConsistency >= 0.82 && totalChecks >= 30) return "Mentor";
        if (weeklyConsistency >= 0.78) return "Elite";
        if (weeklyConsistency >= 0.62) return "Consistent";
        if (weeklyConsistency >= 0.34) return "Rising";
        return "Beginner";
    }

    private AccountabilityDashboardResponse.Profile toProfile(User user, UserProfile profile) {
        return new AccountabilityDashboardResponse.Profile(
                user.getId(),
                defaultUsername(user),
                user.getEmail(),
                profile.getDisplayName(),
                profile.getAvatarUrl(),
                profile.getTimezone(),
                profile.getLanguage(),
                profile.getGoals(),
                profile.getMentorRating()
        );
    }

    private AccountabilityDashboardResponse.Level toLevel(UserStats stats) {
        return new AccountabilityDashboardResponse.Level(
                stats.level(),
                stats.weeklyConsistencyPercent(),
                stats.accountabilityScore(),
                stats.mentorEligible(),
                stats.needsMentor(),
                levelNote(stats)
        );
    }

    private AccountabilityDashboardResponse.MentorMatch toMatch(MentorMatch match) {
        return toMatch(match, Map.of(), Map.of());
    }

    private AccountabilityDashboardResponse.MentorMatch toMatch(
            MentorMatch match,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId
    ) {
        return new AccountabilityDashboardResponse.MentorMatch(
                match.getId(),
                match.getStatus().name(),
                toUserSummary(match.getMentor(), statsByUserId, profilesByUserId),
                toUserSummary(match.getMentee(), statsByUserId, profilesByUserId),
                match.getMatchScore(),
                Arrays.stream(match.getMatchReasons().split("\\|")).map(String::trim).toList(),
                match.isAiMentor()
        );
    }

    private AccountabilityDashboardResponse.UserSummary toUserSummary(
            User user,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId
    ) {
        UserProfile profile = profileFor(user, profilesByUserId);
        UserStats stats = statsFor(user, statsByUserId);
        return new AccountabilityDashboardResponse.UserSummary(
                user.getId(),
                profile.getDisplayName(),
                profile.getTimezone(),
                profile.getLanguage(),
                profile.getGoals(),
                stats.weeklyConsistencyPercent(),
                profile.getMentorRating()
        );
    }

    private AccountabilityDashboardResponse.MenteeSummary toMenteeSummary(
            MentorMatch match,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId
    ) {
        User mentee = match.getMentee();
        UserProfile profile = profileFor(mentee, profilesByUserId);
        UserStats stats = statsFor(mentee, statsByUserId);
        return new AccountabilityDashboardResponse.MenteeSummary(
                match.getId(),
                mentee.getId(),
                profile.getDisplayName(),
                stats.missedToday(),
                stats.weeklyConsistencyPercent(),
                stats.missedToday() > 0 ? "Send a gentle nudge" : "Celebrate today's progress"
        );
    }

    private UserProfile profileFor(User user, Map<Long, UserProfile> cache) {
        UserProfile cached = cache.get(user.getId());
        return cached != null ? cached : ensureProfile(user);
    }

    private AccountabilityDashboardResponse.Message toMessage(MentorshipMessage message) {
        UserProfile senderProfile = ensureProfile(message.getSender());
        return new AccountabilityDashboardResponse.Message(
                message.getId(),
                message.getSender().getId(),
                senderProfile.getDisplayName(),
                message.getMessage(),
                message.isNudge(),
                message.getCreatedAt()
        );
    }

    private AccountabilityDashboardResponse.SocialPost toSocialPost(SocialPost post) {
        return new AccountabilityDashboardResponse.SocialPost(
                post.getId(),
                ensureProfile(post.getAuthor()).getDisplayName(),
                post.getMessage(),
                post.getCreatedAt()
        );
    }

    private AccountabilityDashboardResponse.MentorshipStatus mentorshipStatusFor(UserStats stats, MentorMatch match) {
        boolean canFindMentor = canFindMentor(stats);
        boolean hasMentor = match != null;
        boolean canChangeMentor = hasMentor && !isMentorLocked(match);
        Instant lockedUntil = hasMentor && isMentorLocked(match) ? mentorLockedUntil(match) : null;
        int lockDaysRemaining = lockedUntil == null
                ? 0
                : Math.max(1, (int) ChronoUnit.DAYS.between(Instant.now(), lockedUntil) + 1);

        String message;
        if (!canFindMentor) {
            message = mentorMatchingWaitMessage(stats);
        } else if (!hasMentor) {
            message = "We will assign a mentor automatically using your 7-day habit trend as soon as another user is available.";
        } else if (canChangeMentor) {
            message = "You can change mentor if this match is not helping.";
        } else {
            message = "This mentor match is locked for " + lockDaysRemaining + " more " + (lockDaysRemaining == 1 ? "day" : "days") + ".";
        }

        return new AccountabilityDashboardResponse.MentorshipStatus(
                canFindMentor,
                hasMentor,
                canChangeMentor,
                lockedUntil,
                lockDaysRemaining,
                message
        );
    }

    private AccountabilityDashboardResponse.SocialDashboard socialDashboardFor(
            User user,
            UserProfile viewerProfile,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId,
            List<User> following,
            List<User> socialCandidates
    ) {
        return new AccountabilityDashboardResponse.SocialDashboard(
                following.size(),
                following.stream()
                        .map(friend -> toSocialActivity(friend, statsByUserId, profilesByUserId))
                        .limit(10)
                        .toList(),
                suggestedFriends(user, viewerProfile, statsByUserId, profilesByUserId, socialCandidates).stream()
                        .map(candidate -> toFriendSummary(candidate.user(), statsByUserId, profilesByUserId))
                        .limit(5)
                        .toList()
        );
    }

    private AccountabilityDashboardResponse.SocialActivity toSocialActivity(
            User friend,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId
    ) {
        UserProfile profile = profileFor(friend, profilesByUserId);
        UserStats stats = statsFor(friend, statsByUserId);
        int progressPercent = progressPercent(stats);
        String todayKey = LocalDate.now().toString();
        String kind = progressPercent >= 100 ? "PERFECT_DAY" : stats.weeklyConsistencyPercent() >= 80 ? "CONSISTENCY" : "PROGRESS";
        String message = switch (kind) {
            case "PERFECT_DAY" -> "Finished every habit today.";
            case "CONSISTENCY" -> "Holding a strong week with steady check-ins.";
            default -> progressPercent > 0
                    ? "Made progress today without sharing habit details."
                    : "Still building momentum for today.";
        };

        return new AccountabilityDashboardResponse.SocialActivity(
                "friend-" + friend.getId() + "-" + todayKey,
                friend.getId(),
                profile.getDisplayName(),
                message,
                stats.weeklyConsistencyPercent(),
                progressPercent,
                stats.yearPerfectDays(),
                kind,
                null
        );
    }

    private AccountabilityDashboardResponse.FriendSummary toFriendSummary(
            User friend,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId
    ) {
        UserProfile profile = profileFor(friend, profilesByUserId);
        UserStats stats = statsFor(friend, statsByUserId);
        return new AccountabilityDashboardResponse.FriendSummary(
                friend.getId(),
                profile.getDisplayName(),
                stats.weeklyConsistencyPercent(),
                progressPercent(stats),
                profile.getGoals()
        );
    }

    private List<User> followingUsers(User user) {
        return friendRepo.findAllByRequesterAndStatus(user, FriendConnectionStatus.ACCEPTED).stream()
                .map(FriendConnection::getAddressee)
                .toList();
    }

    private List<FriendCandidate> suggestedFriends(
            User user,
            UserProfile profile,
            Map<Long, UserStats> statsByUserId,
            Map<Long, UserProfile> profilesByUserId,
            List<User> candidatePool
    ) {
        Set<Long> followingIds = followingIds(user);
        Map<Long, Integer> secondDegreeScores = secondDegreeFollowScores(user, followingIds);
        return candidatePool.stream()
                .filter(candidate -> !followingIds.contains(candidate.getId()))
                .filter(candidate -> isDiscoverableSocialCandidate(candidate, statsByUserId))
                .map(candidate -> {
                    UserProfile candidateProfile = profileFor(candidate, profilesByUserId);
                    UserStats stats = statsFor(candidate, statsByUserId);
                    int goalScore = (int) Math.round(goalOverlap(profile.getGoals(), candidateProfile.getGoals()) * 30);
                    int timezoneScore = timezoneScore(profile.getTimezone(), candidateProfile.getTimezone());
                    int languageScore = profile.getLanguage().equalsIgnoreCase(candidateProfile.getLanguage()) ? 8 : 0;
                    int progressScore = progressPercent(stats) / 4;
                    int mentorRatingScore = Math.max(0, candidateProfile.getMentorRating() - MENTOR_RATING_DEFAULT) / 2;
                    int secondDegreeScore = Math.min(secondDegreeScores.getOrDefault(candidate.getId(), 0) * 18, 54);
                    int score = stats.weeklyConsistencyPercent()
                            + goalScore
                            + timezoneScore
                            + languageScore
                            + progressScore
                            + mentorRatingScore
                            + secondDegreeScore;
                    return new FriendCandidate(candidate, score);
                })
                .sorted(Comparator.comparingInt(FriendCandidate::score).reversed())
                .limit(10)
                .toList();
    }

    private boolean isDiscoverableSocialCandidate(User candidate, Map<Long, UserStats> statsByUserId) {
        // SQL filter already excludes the AI mentor; defensive check kept for
        // callers that pass arbitrary candidates.
        if (isAiMentor(candidate)) {
            return false;
        }
        UserStats stats = statsFor(candidate, statsByUserId);
        return stats.totalHabits() > 0 || stats.totalChecks() > 0;
    }

    private Set<Long> followingIds(User user) {
        return friendRepo.findAllByRequesterAndStatus(user, FriendConnectionStatus.ACCEPTED).stream()
                .map(connection -> connection.getAddressee().getId())
                .collect(Collectors.toSet());
    }

    private Map<Long, Integer> secondDegreeFollowScores(User user, Set<Long> followingIds) {
        Map<Long, Integer> scores = new HashMap<>();
        List<User> following = followingUsers(user);
        for (User followedUser : following) {
            for (FriendConnection connection : friendRepo.findAllByRequesterAndStatus(followedUser, FriendConnectionStatus.ACCEPTED)) {
                Long candidateId = connection.getAddressee().getId();
                if (!candidateId.equals(user.getId()) && !followingIds.contains(candidateId)) {
                    scores.merge(candidateId, 1, Integer::sum);
                }
            }
        }
        return scores;
    }

    private int progressPercent(UserStats stats) {
        if (stats.totalHabits() == 0) return 0;
        return Math.min(100, (int) Math.round((double) stats.doneToday() / (double) stats.totalHabits() * 100));
    }

    private boolean canFindMentor(UserStats stats) {
        return stats.totalHabits() > 0 && stats.historyDays() >= MENTOR_DATA_DAYS;
    }

    private String mentorMatchingWaitMessage(UserStats stats) {
        if (stats.totalHabits() == 0) {
            return "Add your first habit to start collecting accountability data.";
        }

        int daysRemaining = Math.max(0, MENTOR_DATA_DAYS - stats.historyDays());
        return "Mentor matching starts after " + MENTOR_DATA_DAYS + " days of habit data. "
                + daysRemaining + " more " + (daysRemaining == 1 ? "day" : "days")
                + " needed to assign a mentor from your trend and consistency.";
    }

    private boolean isMentorLocked(MentorMatch match) {
        return LIVE_MATCH_STATUSES.contains(match.getStatus()) && Instant.now().isBefore(mentorLockedUntil(match));
    }

    private Instant mentorLockedUntil(MentorMatch match) {
        return match.getCreatedAt().plus(MENTOR_LOCK_DAYS, ChronoUnit.DAYS);
    }

    private void endMatch(MentorMatch match) {
        endMatch(match, "Mentor match ended.");
    }

    private void endMatch(MentorMatch match, String reason) {
        match.setStatus(MentorMatchStatus.ENDED);
        match.setEndedReason(reason);
        match.setEndedAt(Instant.now());
        matchRepo.save(match);
    }

    private boolean isFollowing(User follower, User followed) {
        return friendRepo.findByRequesterAndAddressee(follower, followed)
                .map(connection -> connection.getStatus() == FriendConnectionStatus.ACCEPTED)
                .orElse(false);
    }

    private boolean matchesSearch(User candidate, Map<Long, UserProfile> profilesByUserId, String query) {
        UserProfile profile = profileFor(candidate, profilesByUserId);
        // Deliberately exclude email from searchable fields — exposing it
        // would turn the search endpoint into an enumeration oracle for PII.
        return containsIgnoreCase(candidate.getUsername(), query)
                || containsIgnoreCase(profile.getDisplayName(), query)
                || containsIgnoreCase(profile.getGoals(), query);
    }

    private boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private AccountabilityDashboardResponse.WeeklyChallenge weeklyChallengeFor(User user, UserStats stats) {
        WeeklyChallengeTemplate template = weeklyChallengeTemplate(user, stats);
        int target = template.targetPerfectDays();
        int score = Math.min(stats.recentPerfectDays(), target);
        String displayName = ensureProfile(user).getDisplayName();
        return new AccountabilityDashboardResponse.WeeklyChallenge(
                template.title(),
                template.description(),
                score,
                target,
                1,
                List.of(
                        new AccountabilityDashboardResponse.LeaderboardEntry(displayName, score, true, stats.verifiedScore())
                )
        );
    }

    private WeeklyChallengeTemplate weeklyChallengeTemplate(User user, UserStats stats) {
        int week = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int selector = Math.floorMod(Objects.hash(user.getId(), week, stats.level()), 5);

        if (stats.totalHabits() == 0) {
            return new WeeklyChallengeTemplate(
                    "Start your first streak",
                    "Add a habit and complete it on 3 days this week.",
                    3
            );
        }

        if (stats.weeklyConsistencyPercent() < 45) {
            return switch (selector % 2) {
                case 0 -> new WeeklyChallengeTemplate(
                        "3-day reset",
                        "Complete every active habit on 3 days this week.",
                        3
                );
                default -> new WeeklyChallengeTemplate(
                        "Small wins week",
                        "Aim for 3 perfect days. Keep the target reachable and repeatable.",
                        3
                );
            };
        }

        if (stats.weeklyConsistencyPercent() < 75) {
            return switch (selector % 2) {
                case 0 -> new WeeklyChallengeTemplate(
                        "Consistency climb",
                        "Complete every active habit on 4 days this week.",
                        4
                );
                default -> new WeeklyChallengeTemplate(
                        "Four focused days",
                        "Stack 4 perfect days before the week ends.",
                        4
                );
            };
        }

        return switch (selector) {
            case 0 -> new WeeklyChallengeTemplate(
                    "5 focused days",
                    "Complete every active habit on 5 days this week.",
                    5
            );
            case 1 -> new WeeklyChallengeTemplate(
                    "Streak builder",
                    "Protect your rhythm with 5 perfect days this week.",
                    5
            );
            case 2 -> new WeeklyChallengeTemplate(
                    "Elite consistency",
                    "Push for 6 perfect days while keeping the pace sustainable.",
                    6
            );
            case 3 -> new WeeklyChallengeTemplate(
                    "No-zero week",
                    "Finish every active habit on at least 5 days this week.",
                    5
            );
            default -> new WeeklyChallengeTemplate(
                    "Strong finish",
                    "Close the week with 5 perfect days.",
                    5
            );
        };
    }

    private List<AccountabilityDashboardResponse.Notification> notificationsFor(
            UserStats stats,
            MentorMatch ownMatch,
            List<MentorMatch> mentorMatches
    ) {
        List<AccountabilityDashboardResponse.Notification> out = new ArrayList<>();
        if (ownMatch != null) {
            out.add(new AccountabilityDashboardResponse.Notification(
                    "Your mentor checked in",
                    "Reply with one tiny next step.",
                    "MENTOR_CHECK_IN"
            ));
        }
        if (!stats.mentorEligible()) {
            out.add(new AccountabilityDashboardResponse.Notification(
                    "You are building mentor eligibility",
                    "Stay consistent this week to unlock mentor review.",
                    "MENTOR_PROGRESS"
            ));
        }
        if (stats.mentorEligible() && mentorMatches.isEmpty()) {
            out.add(new AccountabilityDashboardResponse.Notification(
                    "Someone needs guidance from you",
                    "You can support a struggling user with a gentle nudge.",
                    "GUIDANCE_REQUEST"
            ));
        }
        return out;
    }

    private List<String> badgesFor(UserStats stats) {
        List<String> badges = new ArrayList<>();
        if (stats.totalChecks() > 0) badges.add("First Check");
        if (stats.perfectDays() > 0) badges.add("Perfect Day");
        if (stats.bestStreak() >= 7) badges.add("7-Day Streak");
        if (stats.mentorEligible()) badges.add("Mentor Eligible");
        return badges;
    }

    private List<AccountabilityDashboardResponse.HabitTimeCluster> habitClustersFor(User user) {
        List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, HabitEntryType.HABIT);
        if (habits.isEmpty()) return List.of();

        UserProfile profile = ensureProfile(user);
        ZoneId zone;
        try {
            zone = ZoneId.of(profile.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        final ZoneId userZone = zone;

        List<HabitCheck> checks = checkRepo.findAllByHabitIn(habits);
        // Group done checks that have completedAt by habitId
        Map<Long, List<HabitCheck>> timedByHabit = checks.stream()
                .filter(HabitCheck::isDone)
                .filter(hc -> hc.getCompletedAt() != null)
                .collect(Collectors.groupingBy(hc -> hc.getHabit().getId()));

        return habits.stream()
                .sorted(Comparator.comparingLong(Habit::getId))
                .map(habit -> {
                    List<HabitCheck> timed = timedByHabit.getOrDefault(habit.getId(), List.of());
                    int sampleSize = timed.size();
                    if (sampleSize < 3) {
                        return new AccountabilityDashboardResponse.HabitTimeCluster(
                                habit.getId(),
                                habit.getTitle(),
                                "UNKNOWN",
                                -1,
                                sampleSize
                        );
                    }
                    // Compute hours in user's timezone
                    List<Integer> hours = timed.stream()
                            .map(hc -> hc.getCompletedAt().atZone(userZone).getHour())
                            .toList();
                    double avgHour = hours.stream().mapToInt(Integer::intValue).average().orElse(0);
                    int avgHourInt = (int) Math.round(avgHour);

                    // Compute standard deviation
                    double variance = hours.stream()
                            .mapToDouble(h -> Math.pow(h - avgHour, 2))
                            .average()
                            .orElse(0);
                    double stdDev = Math.sqrt(variance);

                    String timeSlot;
                    if (stdDev > 4) {
                        timeSlot = "MIXED";
                    } else if (avgHourInt >= 5 && avgHourInt <= 11) {
                        timeSlot = "MORNING";
                    } else if (avgHourInt >= 12 && avgHourInt <= 16) {
                        timeSlot = "AFTERNOON";
                    } else if (avgHourInt >= 17 && avgHourInt <= 20) {
                        timeSlot = "EVENING";
                    } else {
                        timeSlot = "NIGHT";
                    }

                    return new AccountabilityDashboardResponse.HabitTimeCluster(
                            habit.getId(),
                            habit.getTitle(),
                            timeSlot,
                            avgHourInt,
                            sampleSize
                    );
                })
                .toList();
    }

    private String mentorTip(UserStats stats) {
        if (stats.missedToday() > 0) {
            return "Pick the smallest missed habit and tell your mentor when it is done.";
        }
        if (stats.doneToday() == stats.totalHabits() && stats.totalHabits() > 0) {
            return "Today is complete. Share what made it easier so your mentor can reinforce it.";
        }
        return "Keep the next step tiny. Accountability should feel supportive, not heavy.";
    }

    private String levelNote(UserStats stats) {
        if (stats.needsMentor()) {
            return "A mentor can help you restart without judgment.";
        }
        if (stats.mentorEligible()) {
            return "You are eligible to mentor another user.";
        }
        return "Track for 7 days, then mentor matching becomes more accurate.";
    }

    private UserProfile ensureProfile(User user) {
        return profileRepo.findByUser(user).orElseGet(() -> profileRepo.save(UserProfile.builder()
                .user(user)
                .displayName(defaultDisplayName(user))
                .avatarUrl(null)
                .timezone(ZoneId.systemDefault().getId())
                .language(Locale.getDefault().getLanguage().toUpperCase())
                .goals("Daily consistency")
                .build()));
    }

    private String defaultDisplayName(User user) {
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        String email = user.getEmail();
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "User " + user.getId();
    }

    private String defaultUsername(User user) {
        return user.getUsername() == null || user.getUsername().isBlank()
                ? defaultDisplayName(user)
                : user.getUsername();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private User requireUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private MentorMatch requireMatchParticipant(Long matchId, User user) {
        MentorMatch match = matchRepo.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Mentor match not found"));
        if (!match.getMentor().getId().equals(user.getId()) && !match.getMentee().getId().equals(user.getId())) {
            throw new IllegalArgumentException("You are not part of this mentorship");
        }
        return match;
    }

    private record UserStats(
            int totalHabits,
            int doneToday,
            int missedToday,
            int totalChecks,
            double weeklyConsistency,
            int weeklyConsistencyPercent,
            int accountabilityScore,
            String level,
            boolean mentorEligible,
            boolean needsMentor,
            int xp,
            int perfectDays,
            int bestStreak,
            int recentPerfectDays,
            int historyDays,
            int checksToday,
            boolean rewardEligible,
            int yearPerfectDays,
            /// Tier-weighted score for this ISO week. auto × 10, partial × 5,
            /// selfReport × 1, null tier × 0. Drives the leaderboard's
            /// anti-cheat ranking — users who actually verified their
            /// habits via HealthKit out-earn users who only self-reported.
            int verifiedScore
    ) {
    }

    /// Per-user reward state (XP totals, daily-cap counters). Pulled together
    /// only for the viewer's own dashboard — friends/suggestions don't surface
    /// these fields, so the bulk loader passes EMPTY_REWARDS for everyone else
    /// and skips the reward queries entirely.
    private record RewardSnapshot(int xp, int checksToday, boolean rewardEligible) {}

    private static final RewardSnapshot EMPTY_REWARDS = new RewardSnapshot(0, 0, true);

    private record MentorCandidateScore(
            User user,
            UserStats stats,
            int score,
            List<String> reasons
    ) {
    }

    private record FriendCandidate(
            User user,
            int score
    ) {
    }

    private record MessageReadEvent(
            Long matchId,
            Long userId,
            Instant at
    ) {
    }

    private record WeeklyChallengeTemplate(
            String title,
            String description,
            int targetPerfectDays
    ) {
    }
}
