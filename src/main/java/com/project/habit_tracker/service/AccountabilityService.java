package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AccountabilityDashboardResponse;
import com.project.habit_tracker.api.dto.MentorshipMessageRequest;
import com.project.habit_tracker.api.dto.ProfileRequest;
import com.project.habit_tracker.entity.*;
import com.project.habit_tracker.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccountabilityService {
    private static final int MENTOR_LOCK_DAYS = 7;
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
            StreakFreezeService streakFreezeService
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
    }

    @Transactional
    public AccountabilityDashboardResponse dashboard(Long userId) {
        User user = requireUser(userId);
        UserProfile profile = ensureProfile(user);
        UserStats stats = statsFor(user);

        Optional<MentorMatch> ownMatch = matchRepo.findFirstByMenteeAndStatusInOrderByCreatedAtDesc(user, LIVE_MATCH_STATUSES);
        List<MentorMatch> mentorMatches = matchRepo.findAllByMentorAndStatusIn(user, LIVE_MATCH_STATUSES);

        return dashboardFor(user, profile, stats, ownMatch.orElse(null), mentorMatches);
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
            throw new IllegalStateException("Mentor matching unlocks after 7 days of habit data.");
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

        existing.ifPresent(current -> {
            endMatch(current);
            streamService.publishToMatch(current.getId(), "match.updated", toMatch(current));
        });

        MentorMatch match = MentorMatch.builder()
                .mentor(best.user())
                .mentee(mentee)
                .status(MentorMatchStatus.ACTIVE)
                .matchScore(best.score())
                .matchReasons(String.join(" | ", best.reasons()))
                .createdAt(Instant.now())
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

        return dashboardFor(mentee, menteeProfile, menteeStats, match, matchRepo.findAllByMentorAndStatusIn(mentee, LIVE_MATCH_STATUSES));
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
        MentorshipMessage saved = messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(sender)
                .message(req.message().trim())
                .nudge(nudge)
                .createdAt(Instant.now())
                .build());
        streamService.publishToMatch(matchId, "message.created", toMessage(saved));
        streamService.publishToMatch(matchId, "match.updated", toMatch(match));

        if (nudge) {
            pushNudgeToReceiver(sender, match, req.message().trim());
        }

        return dashboard(userId);
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
                apnsService.sendNudge(dt.getToken(), senderName, message)
        );
    }

    @Transactional(readOnly = true)
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
            throw new IllegalArgumentException("You cannot add yourself as a friend");
        }

        Optional<FriendConnection> existing = friendRepo.findBetween(requester, addressee);
        if (existing.isEmpty()) {
            Instant now = Instant.now();
            friendRepo.save(FriendConnection.builder()
                    .requester(requester)
                    .addressee(addressee)
                    .status(FriendConnectionStatus.ACCEPTED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } else if (existing.get().getStatus() == FriendConnectionStatus.PENDING) {
            FriendConnection connection = existing.get();
            connection.setStatus(FriendConnectionStatus.ACCEPTED);
            connection.setUpdatedAt(Instant.now());
            friendRepo.save(connection);
        }

        return dashboard(userId);
    }

    private AccountabilityDashboardResponse dashboardFor(
            User user,
            UserProfile profile,
            UserStats stats,
            MentorMatch ownMatch,
            List<MentorMatch> mentorMatches
    ) {
        List<MentorshipMessage> messages = ownMatch == null ? List.of() : messageRepo.findTop20ByMatchOrderByCreatedAtDesc(ownMatch);
        return new AccountabilityDashboardResponse(
                toProfile(user, profile),
                toLevel(stats),
                ownMatch == null ? null : toMatch(ownMatch),
                new AccountabilityDashboardResponse.MenteeDashboard(
                        mentorTip(stats),
                        stats.missedToday(),
                        stats.accountabilityScore(),
                        messages.stream().map(this::toMessage).toList()
                ),
                new AccountabilityDashboardResponse.MentorDashboard(
                        mentorMatches.size(),
                        mentorMatches.stream().map(this::toMenteeSummary).toList()
                ),
                mentorshipStatusFor(stats, ownMatch),
                new AccountabilityDashboardResponse.Rewards(
                        stats.xp(),
                        badgesFor(stats),
                        stats.checksToday(),
                        RewardService.DAILY_GRANT_CAP,
                        stats.rewardEligible(),
                        streakFreezeService.availableFreezes(user),
                        streakFreezeService.frozenDates(user)
                ),
                weeklyChallengeFor(user, stats),
                socialDashboardFor(user),
                feed(20),
                notificationsFor(stats, ownMatch, mentorMatches),
                habitClustersFor(user)
        );
    }

    private Optional<MentorCandidateScore> bestMentorFor(User mentee, UserProfile menteeProfile, Set<Long> excludedUserIds) {
        return userRepo.findAll().stream()
                .filter(candidate -> !candidate.getId().equals(mentee.getId()))
                .filter(candidate -> !excludedUserIds.contains(candidate.getId()))
                .map(candidate -> scoreCandidate(candidate, menteeProfile))
                .flatMap(Optional::stream)
                .filter(score -> score.stats().mentorEligible())
                .max(Comparator.comparingInt(MentorCandidateScore::score));
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
        int friendScore = areFriends(menteeProfile.getUser(), candidate) ? 20 : 0;
        int loadPenalty = (int) Math.min(matchRepo.countByMentorAndStatus(candidate, MentorMatchStatus.ACTIVE) * 8, 24);
        int score = Math.max(0, consistencyScore + goalScore + timezoneScore + languageScore + friendScore - loadPenalty);

        List<String> reasons = new ArrayList<>();
        reasons.add(stats.weeklyConsistencyPercent() + "% consistency");
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
        List<Habit> habits = habitRepo.findAllByUser(user);
        List<HabitCheck> checks = habits.isEmpty() ? List.of() : checkRepo.findAllByHabitIn(habits);
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
        // XP comes from persisted grants — idempotent and cap-aware
        int xp = rewardService.totalXp(user);
        int checksToday    = rewardService.checksGrantedToday(user);
        boolean rewardEligible = rewardService.isRewardEligible(user);
        int recentPerfectDays = recentPerfectDays(habits, checks);

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
                xp,
                perfectDays,
                bestStreak,
                recentPerfectDays,
                historyDays,
                checksToday,
                rewardEligible
        );
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
                profile.getGoals()
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
        return new AccountabilityDashboardResponse.MentorMatch(
                match.getId(),
                match.getStatus().name(),
                toUserSummary(match.getMentor()),
                toUserSummary(match.getMentee()),
                match.getMatchScore(),
                Arrays.stream(match.getMatchReasons().split("\\|")).map(String::trim).toList()
        );
    }

    private AccountabilityDashboardResponse.UserSummary toUserSummary(User user) {
        UserProfile profile = ensureProfile(user);
        UserStats stats = statsFor(user);
        return new AccountabilityDashboardResponse.UserSummary(
                user.getId(),
                profile.getDisplayName(),
                profile.getTimezone(),
                profile.getLanguage(),
                profile.getGoals(),
                stats.weeklyConsistencyPercent()
        );
    }

    private AccountabilityDashboardResponse.MenteeSummary toMenteeSummary(MentorMatch match) {
        User mentee = match.getMentee();
        UserProfile profile = ensureProfile(mentee);
        UserStats stats = statsFor(mentee);
        return new AccountabilityDashboardResponse.MenteeSummary(
                match.getId(),
                mentee.getId(),
                profile.getDisplayName(),
                stats.missedToday(),
                stats.weeklyConsistencyPercent(),
                stats.missedToday() > 0 ? "Send a gentle nudge" : "Celebrate today's progress"
        );
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
            int daysRemaining = Math.max(0, 7 - stats.historyDays());
            message = "Mentor matching unlocks after " + daysRemaining + " more " + (daysRemaining == 1 ? "day" : "days") + " of data.";
        } else if (!hasMentor) {
            message = "Find a mentor when you want extra accountability.";
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

    private AccountabilityDashboardResponse.SocialDashboard socialDashboardFor(User user) {
        List<User> friends = acceptedFriends(user);
        return new AccountabilityDashboardResponse.SocialDashboard(
                friends.size(),
                friends.stream()
                        .map(this::toSocialActivity)
                        .limit(10)
                        .toList(),
                suggestedFriends(user).stream()
                        .map(candidate -> toFriendSummary(candidate.user()))
                        .limit(5)
                        .toList()
        );
    }

    private AccountabilityDashboardResponse.SocialActivity toSocialActivity(User friend) {
        UserProfile profile = ensureProfile(friend);
        UserStats stats = statsFor(friend);
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
                kind,
                null
        );
    }

    private AccountabilityDashboardResponse.FriendSummary toFriendSummary(User friend) {
        UserProfile profile = ensureProfile(friend);
        UserStats stats = statsFor(friend);
        return new AccountabilityDashboardResponse.FriendSummary(
                friend.getId(),
                profile.getDisplayName(),
                stats.weeklyConsistencyPercent(),
                progressPercent(stats),
                profile.getGoals()
        );
    }

    private List<User> acceptedFriends(User user) {
        return friendRepo.findAllByUserAndStatus(user, FriendConnectionStatus.ACCEPTED).stream()
                .map(connection -> otherUser(connection, user))
                .toList();
    }

    private List<FriendCandidate> suggestedFriends(User user) {
        UserProfile profile = ensureProfile(user);
        Set<Long> connectedUserIds = connectedUserIds(user);
        return userRepo.findAll().stream()
                .filter(candidate -> !candidate.getId().equals(user.getId()))
                .filter(candidate -> !connectedUserIds.contains(candidate.getId()))
                .map(candidate -> {
                    UserProfile candidateProfile = ensureProfile(candidate);
                    UserStats stats = statsFor(candidate);
                    int goalScore = (int) Math.round(goalOverlap(profile.getGoals(), candidateProfile.getGoals()) * 30);
                    int score = stats.weeklyConsistencyPercent() + goalScore;
                    return new FriendCandidate(candidate, score);
                })
                .sorted(Comparator.comparingInt(FriendCandidate::score).reversed())
                .limit(10)
                .toList();
    }

    private Set<Long> connectedUserIds(User user) {
        return friendRepo.findAllByUserAndStatusIn(
                        user,
                        List.of(FriendConnectionStatus.PENDING, FriendConnectionStatus.ACCEPTED)
                ).stream()
                .map(connection -> otherUser(connection, user).getId())
                .collect(Collectors.toSet());
    }

    private User otherUser(FriendConnection connection, User user) {
        return connection.getRequester().getId().equals(user.getId())
                ? connection.getAddressee()
                : connection.getRequester();
    }

    private int progressPercent(UserStats stats) {
        if (stats.totalHabits() == 0) return 0;
        return Math.min(100, (int) Math.round((double) stats.doneToday() / (double) stats.totalHabits() * 100));
    }

    private boolean canFindMentor(UserStats stats) {
        return stats.totalHabits() > 0 && stats.historyDays() >= 7;
    }

    private boolean isMentorLocked(MentorMatch match) {
        return LIVE_MATCH_STATUSES.contains(match.getStatus()) && Instant.now().isBefore(mentorLockedUntil(match));
    }

    private Instant mentorLockedUntil(MentorMatch match) {
        return match.getCreatedAt().plus(MENTOR_LOCK_DAYS, ChronoUnit.DAYS);
    }

    private void endMatch(MentorMatch match) {
        match.setStatus(MentorMatchStatus.ENDED);
        match.setEndedAt(Instant.now());
        matchRepo.save(match);
    }

    private boolean areFriends(User left, User right) {
        return friendRepo.findBetween(left, right)
                .map(connection -> connection.getStatus() == FriendConnectionStatus.ACCEPTED)
                .orElse(false);
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
                        new AccountabilityDashboardResponse.LeaderboardEntry(displayName, score, true)
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
        List<Habit> habits = habitRepo.findAllByUser(user);
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
            boolean rewardEligible
    ) {
    }

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
