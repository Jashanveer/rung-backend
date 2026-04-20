package com.project.habit_tracker.api.dto;

import java.time.Instant;
import java.util.List;

public record AccountabilityDashboardResponse(
        Profile profile,
        Level level,
        MentorMatch match,
        MenteeDashboard menteeDashboard,
        MentorDashboard mentorDashboard,
        MentorshipStatus mentorship,
        Rewards rewards,
        WeeklyChallenge weeklyChallenge,
        SocialDashboard social,
        List<SocialPost> feed,
        List<Notification> notifications,
        List<HabitTimeCluster> habitClusters
) {
    public record Profile(
            Long userId,
            String username,
            String email,
            String displayName,
            String avatarUrl,
            String timezone,
            String language,
            String goals,
            int mentorRating
    ) {
    }

    public record Level(
            String name,
            int weeklyConsistencyPercent,
            int accountabilityScore,
            boolean mentorEligible,
            boolean needsMentor,
            String note
    ) {
    }

    public record MentorMatch(
            Long id,
            String status,
            UserSummary mentor,
            UserSummary mentee,
            int matchScore,
            List<String> reasons,
            boolean aiMentor
    ) {
    }

    public record UserSummary(
            Long userId,
            String displayName,
            String timezone,
            String language,
            String goals,
            int weeklyConsistencyPercent,
            int mentorRating
    ) {
    }

    public record MenteeDashboard(
            String mentorTip,
            int missedHabitsToday,
            int progressScore,
            List<Message> messages
    ) {
    }

    public record MentorDashboard(
            int activeMenteeCount,
            List<MenteeSummary> mentees
    ) {
    }

    public record MentorshipStatus(
            boolean canFindMentor,
            boolean hasMentor,
            boolean canChangeMentor,
            Instant lockedUntil,
            int lockDaysRemaining,
            String message
    ) {
    }

    public record MenteeSummary(
            Long matchId,
            Long userId,
            String displayName,
            int missedHabitsToday,
            int weeklyConsistencyPercent,
            String suggestedAction
    ) {
    }

    public record Rewards(
            int xp,
            List<String> badges,
            /** How many unique habit-day checks have earned XP today. */
            int checksToday,
            /** Daily cap — checks beyond this still count but earn 0 XP. */
            int dailyCap,
            /** False when the user has reached the daily cap and will earn 0 XP on the next check. */
            boolean rewardEligible,
            /** Number of unused streak freeze tokens. */
            int freezesAvailable,
            /** Date keys for which a streak freeze has been used. */
            List<String> frozenDates
    ) {
    }

    public record HabitTimeCluster(
            Long habitId,
            String habitTitle,
            String timeSlot,   // "MORNING" | "AFTERNOON" | "EVENING" | "NIGHT" | "MIXED" | "UNKNOWN"
            int avgHourOfDay,  // 0-23 average completion hour, -1 if unknown
            int sampleSize     // number of completions with time data
    ) {
    }

    public record WeeklyChallenge(
            String title,
            String description,
            int completedPerfectDays,
            int targetPerfectDays,
            int rank,
            List<LeaderboardEntry> leaderboard
    ) {
    }

    public record LeaderboardEntry(
            String displayName,
            int score,
            boolean currentUser
    ) {
    }

    public record SocialPost(
            Long id,
            String author,
            String message,
            Instant createdAt
    ) {
    }

    public record SocialDashboard(
            int friendCount,
            List<SocialActivity> updates,
            List<FriendSummary> suggestions
    ) {
    }

    public record SocialActivity(
            String id,
            Long userId,
            String displayName,
            String message,
            int weeklyConsistencyPercent,
            int progressPercent,
            String kind,
            Instant createdAt
    ) {
    }

    public record FriendSummary(
            Long userId,
            String displayName,
            int weeklyConsistencyPercent,
            int progressPercent,
            String goals
    ) {
    }

    public record Message(
            Long id,
            Long senderId,
            String senderName,
            String message,
            boolean nudge,
            Instant createdAt
    ) {
    }

    public record Notification(
            String title,
            String body,
            String type
    ) {
    }
}
