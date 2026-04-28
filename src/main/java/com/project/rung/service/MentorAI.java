package com.project.rung.service;

import java.util.List;

/**
 * Common surface for the AI mentor — swapped behind `mentor.provider` so
 * both {@link AIService} (Anthropic) and {@link GeminiMentorAI} (Google Gemini)
 * can answer chat replies, welcomes, check-ins, and weekly-email insights
 * without any caller caring which model actually runs.
 *
 * Records live here (not on the implementations) so downstream code passes
 * values through without coupling to a specific provider.
 */
public interface MentorAI {

    /// True when the provider has a configured API key. Callers MUST gate
    /// every generation path on this — a missing key should never throw.
    boolean isConfigured();

    /// 2–3 sentence personalised insight paragraph for the Sunday weekly
    /// email. Returns "" if the provider isn't configured or the call fails.
    String generateWeeklyInsight(
            String displayName,
            int consistencyPct,
            int perfectDays,
            int bestStreak,
            int totalHabits,
            List<String> habitNames,
            MentorPersonality personality
    );

    /// Very first welcome message from the AI mentor on match creation.
    /// `personality` drives the voice; `memory` may be empty for a brand-new
    /// user. Returns "" on failure — caller falls back to a static line.
    String generateMentorWelcome(MentorContext ctx, MentorPersonality personality, String memory);

    /// Reply to the latest mentee chat message. `history` is chronological
    /// (oldest first); every turn's role is either "user" or "assistant".
    String generateMentorReply(
            MentorContext ctx,
            MentorPersonality personality,
            String memory,
            List<ChatTurn> history,
            String latestMenteeMessage
    );

    /// One of the 4 windowed nudges (morning / midday / evening / night).
    /// Caller has already decided the user has open habits and is due a
    /// check-in for this window — the implementation only writes copy.
    String generatePeriodicCheckIn(
            MentorContext ctx,
            MentorPersonality personality,
            String memory,
            CheckInWindow window
    );

    /// Drafts an overload warning in-character when the mentee looks
    /// stretched thin (too many habits, low consistency). Empty string if
    /// the model decides the assessment doesn't warrant a message or the
    /// call fails.
    String generateOverloadNudge(
            MentorContext ctx,
            MentorPersonality personality,
            String memory,
            OverloadAssessment assessment
    );

    /// Compresses the latest chat into an updated ~100-word third-person
    /// summary of the mentee — their moods, sticking points, wins,
    /// preferences. Re-injected on every future call as the "memory".
    /// Returns the NEW memory; caller persists it.
    String distillMemory(MentorContext ctx, List<ChatTurn> recentHistory, String previousMemory);

    // MARK: — shared value types

    /// Snapshot of mentee state passed into every prompt. `habitTimingSummary`
    /// is a pre-formatted one-per-line breakdown of when each habit is
    /// typically completed. `weeklyTargetSummary` lists frequency-based
    /// habits with their current week progress (e.g. "Gym: 2/5 this week")
    /// so the mentor can reference the specific shortfall. `verifiedScore`
    /// is the tier-weighted anti-cheat number — an indirect signal of how
    /// much of the user's consistency is genuinely HealthKit-verified.
    record MentorContext(
            String displayName,
            String timezone,
            String language,
            String goals,
            int totalHabits,
            List<String> habitNames,
            int weeklyConsistencyPercent,
            int perfectDaysThisWeek,
            int bestStreak,
            int historyDays,
            int doneToday,
            int missedToday,
            String habitTimingSummary,
            int verifiedScore,
            String weeklyTargetSummary
    ) {
        /// Display name with a generic fallback for users who haven't yet
        /// completed profile setup. Without this, the AI mentor would greet
        /// users by the auto-generated email-slug username (e.g. "abc123xyz"
        /// for private-relay sign-ins) which sounds robotic and wrong.
        public String safeDisplayName() {
            return (displayName == null || displayName.isBlank()) ? "the user" : displayName;
        }
    }

    /// One turn in the mentor-chat history. `role` is "user" (mentee) or
    /// "assistant" (mentor) — both Anthropic and Gemini accept this shape
    /// with a trivial transform.
    record ChatTurn(String role, String text) {}

    /// Output of the overload detector, fed into `generateOverloadNudge` so
    /// the AI can reference concrete numbers instead of vague concern.
    record OverloadAssessment(
            boolean overloaded,
            int totalHabits,
            int weeklyConsistencyPercent,
            int missedTodayCount,
            List<String> weakestHabitNames
    ) {}

    /// Four personality archetypes the mentor rotates through weekly. Each
    /// is just a different system-prompt block — the mentee profile and
    /// memory are shared across them. Weekly rotation lives in
    /// {@link PersonalityRotator}.
    enum MentorPersonality {
        COACH, CHEERLEADER, SAGE, FRIEND;

        /// Voice/tone block merged into the system prompt. Kept short so
        /// the bulk of the prompt stays the mentee context + memory.
        public String systemPromptBlock() {
            return switch (this) {
                case COACH -> """
                        You are a direct, metrics-first accountability coach.
                        Voice: clipped, no-filler, data-led. Reference actual numbers and habit names.
                        Never use "Great job!", "Keep it up!", or other generic cheerleading.
                        Prescribe one concrete next action the user can do in under 30 minutes.
                        """;
                case CHEERLEADER -> """
                        You are a warm, momentum-first encourager.
                        Voice: upbeat, celebratory of small wins, focused on streaks and progress.
                        Name a specific win (a streak, a perfect day, a habit the user hasn't missed).
                        Never be saccharine — celebration must be grounded in real numbers.
                        End with one tiny action that keeps momentum.
                        """;
                case SAGE -> """
                        You are a patient, reflective mentor.
                        Voice: calm, long-view, curious. Treat rough days as data, not failure.
                        Ask one gentle, specific question that helps the user notice their own pattern.
                        Reference the user's stated goals when relevant. Keep replies to 1–3 sentences.
                        """;
                case FRIEND -> """
                        You are a casual, empathetic friend who also happens to track habits.
                        Voice: relaxed, lowercase-ish, no pressure. Never scold. Never moralise.
                        If the user seems overwhelmed, give them permission to drop one habit today.
                        Suggest one tiny step framed as a favour they can do for tomorrow's self.
                        """;
            };
        }
    }

    /// The four scheduled check-in windows (user's local timezone). Triggered
    /// by {@link com.project.rung.service.MentorCheckInScheduler}
    /// when the user has incomplete habits AND we haven't already fired
    /// for this window today.
    enum CheckInWindow {
        MORNING,  // 7–9 am local — frame the day, suggest first habit
        MIDDAY,   // 12–1 pm local — mid-day status nudge
        EVENING,  // 6–8 pm local — surface what's still open
        NIGHT;    // 9–10 pm local — last-chance / streak-protection

        public String promptHint() {
            return switch (this) {
                case MORNING -> "It's morning. Frame the day in one or two sentences; name the first habit they should knock out. Do not greet generically.";
                case MIDDAY  -> "It's around noon. Say what's done so far vs what's still open. Propose a specific next step for the afternoon.";
                case EVENING -> "It's early evening. Call out the habits still open, pick the one most likely to slip, suggest a 10-minute window to knock it out.";
                case NIGHT   -> "It's late. If a streak is at risk, say so plainly and name the freezer option. Otherwise, one short wind-down reflection.";
            };
        }
    }
}
