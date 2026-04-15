package com.project.habit_tracker.api.dto;

import java.time.Instant;
import java.util.List;

public record AccountabilityDashboardResponse(
        Profile profile,
        Level level,
        MentorMatch match,
        MenteeDashboard menteeDashboard,
        MentorDashboard mentorDashboard,
        Rewards rewards,
        WeeklyChallenge weeklyChallenge,
        List<SocialPost> feed,
        List<Notification> notifications
) {
    public record Profile(
            Long userId,
            String email,
            String displayName,
            String timezone,
            String language,
            String goals
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
            List<String> reasons
    ) {
    }

    public record UserSummary(
            Long userId,
            String displayName,
            String timezone,
            String language,
            String goals,
            int weeklyConsistencyPercent
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
            int coins,
            List<String> badges
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
