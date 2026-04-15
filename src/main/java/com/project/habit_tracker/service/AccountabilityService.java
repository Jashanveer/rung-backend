package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AccountabilityDashboardResponse;
import com.project.habit_tracker.api.dto.MentorshipMessageRequest;
import com.project.habit_tracker.api.dto.ProfileRequest;
import com.project.habit_tracker.api.dto.SocialPostRequest;
import com.project.habit_tracker.entity.*;
import com.project.habit_tracker.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccountabilityService {
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

    public AccountabilityService(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            HabitRepository habitRepo,
            HabitCheckRepository checkRepo,
            MentorMatchRepository matchRepo,
            MentorshipMessageRepository messageRepo,
            SocialPostRepository postRepo
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.habitRepo = habitRepo;
        this.checkRepo = checkRepo;
        this.matchRepo = matchRepo;
        this.messageRepo = messageRepo;
        this.postRepo = postRepo;
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

        Optional<MentorMatch> existing = matchRepo.findFirstByMenteeAndStatusInOrderByCreatedAtDesc(mentee, LIVE_MATCH_STATUSES);
        if (existing.isPresent()) {
            return dashboard(userId);
        }

        MentorCandidateScore best = bestMentorFor(mentee, menteeProfile)
                .orElseThrow(() -> new IllegalStateException("No eligible mentors are available yet"));

        MentorMatch match = MentorMatch.builder()
                .mentor(best.user())
                .mentee(mentee)
                .status(MentorMatchStatus.ACTIVE)
                .matchScore(best.score())
                .matchReasons(String.join(" | ", best.reasons()))
                .createdAt(Instant.now())
                .build();
        matchRepo.save(match);

        messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(best.user())
                .message("I am here with you. What is the smallest habit you can finish today?")
                .nudge(true)
                .createdAt(Instant.now())
                .build());

        return dashboardFor(mentee, menteeProfile, menteeStats, match, matchRepo.findAllByMentorAndStatusIn(mentee, LIVE_MATCH_STATUSES));
    }

    @Transactional
    public AccountabilityDashboardResponse sendMessage(Long userId, Long matchId, MentorshipMessageRequest req, boolean nudge) {
        User sender = requireUser(userId);
        MentorMatch match = requireMatchParticipant(matchId, sender);
        messageRepo.save(MentorshipMessage.builder()
                .match(match)
                .sender(sender)
                .message(req.message().trim())
                .nudge(nudge)
                .createdAt(Instant.now())
                .build());
        return dashboard(userId);
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

    @Transactional
    public AccountabilityDashboardResponse createPost(Long userId, SocialPostRequest req) {
        User author = requireUser(userId);
        postRepo.save(SocialPost.builder()
                .author(author)
                .message(req.message().trim())
                .createdAt(Instant.now())
                .build());
        return dashboard(userId);
    }

    @Transactional
    public List<AccountabilityDashboardResponse.SocialPost> feed() {
        return postRepo.findTop25ByOrderByCreatedAtDesc().stream()
                .map(this::toSocialPost)
                .toList();
    }

    @Transactional
    public AccountabilityDashboardResponse.WeeklyChallenge weeklyChallenge(Long userId) {
        User user = requireUser(userId);
        UserStats stats = statsFor(user);
        return weeklyChallengeFor(user, stats);
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
                new AccountabilityDashboardResponse.Rewards(stats.xp(), stats.coins(), badgesFor(stats)),
                weeklyChallengeFor(user, stats),
                feed(),
                notificationsFor(stats, ownMatch, mentorMatches)
        );
    }

    private Optional<MentorCandidateScore> bestMentorFor(User mentee, UserProfile menteeProfile) {
        return userRepo.findAll().stream()
                .filter(candidate -> !candidate.getId().equals(mentee.getId()))
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
        int loadPenalty = (int) Math.min(matchRepo.countByMentorAndStatus(candidate, MentorMatchStatus.ACTIVE) * 8, 24);
        int score = Math.max(0, consistencyScore + goalScore + timezoneScore + languageScore - loadPenalty);

        List<String> reasons = new ArrayList<>();
        reasons.add(stats.weeklyConsistencyPercent() + "% consistency");
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
        int xp = totalChecks * 12 + perfectDays * 35 + bestStreak * 20;
        int coins = totalChecks * 3 + perfectDays * 25;
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
                coins,
                perfectDays,
                bestStreak,
                recentPerfectDays
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
                user.getEmail(),
                profile.getDisplayName(),
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

    private AccountabilityDashboardResponse.WeeklyChallenge weeklyChallengeFor(User user, UserStats stats) {
        int target = 5;
        int score = Math.min(stats.recentPerfectDays(), target);
        return new AccountabilityDashboardResponse.WeeklyChallenge(
                "5 focused days",
                "Complete every active habit on 5 days this week.",
                score,
                target,
                Math.max(1, 4 - Math.min(score, 3)),
                List.of(
                        new AccountabilityDashboardResponse.LeaderboardEntry("Maya", 5, false),
                        new AccountabilityDashboardResponse.LeaderboardEntry(ensureProfile(user).getDisplayName(), score, true),
                        new AccountabilityDashboardResponse.LeaderboardEntry("Leo", 3, false)
                )
        );
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
                .timezone(ZoneId.systemDefault().getId())
                .language(Locale.getDefault().getLanguage().toUpperCase())
                .goals("Daily consistency")
                .build()));
    }

    private String defaultDisplayName(User user) {
        String email = user.getEmail();
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "User " + user.getId();
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
            int coins,
            int perfectDays,
            int bestStreak,
            int recentPerfectDays
    ) {
    }

    private record MentorCandidateScore(
            User user,
            UserStats stats,
            int score,
            List<String> reasons
    ) {
    }
}
