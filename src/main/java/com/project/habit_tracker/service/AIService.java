package com.project.habit_tracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API. Hosts:
 *   • Weekly insight paragraph for the email digest.
 *   • Mentor chat replies and welcome messages for the AI fallback mentor.
 *
 * Uses RestTemplate directly — no Anthropic SDK dependency required.
 * Mentor chat shapes the system block as a list with cache_control so the
 * per-mentee profile prefix is reused across turns inside the 5-minute TTL.
 */
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private final RestTemplate restTemplate;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    public AIService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Returns a 2–3 sentence personalised insight paragraph for the weekly email.
     * Falls back to an empty string if the API key is absent or the call fails.
     */
    public String generateWeeklyInsight(String displayName, int consistencyPct, int perfectDays,
                                         int bestStreak, int totalHabits, List<String> habitNames) {
        if (!isConfigured()) {
            log.debug("Anthropic API key not set — skipping AI insight");
            return "";
        }

        String prompt = buildWeeklyPrompt(displayName, consistencyPct, perfectDays, bestStreak, totalHabits, habitNames);

        Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 200,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return callForText(body, "weekly insight");
    }

    /**
     * One short opening message from the AI mentor when a match is created.
     * Empty string if the call fails — caller should fall back to a static line.
     */
    public String generateMentorWelcome(MentorContext ctx) {
        if (!isConfigured()) return "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 220);
        body.put("system", buildMentorSystemBlocks(ctx));
        body.put("messages", List.of(
                Map.of("role", "user", "content",
                        "Send the very first welcome message to your new mentee. " +
                        "Two short sentences. Reference one specific thing about their habits or recent week. " +
                        "End with one tiny action they can take in the next hour.")
        ));

        return callForText(body, "mentor welcome");
    }

    /**
     * AI mentor reply to the latest mentee message. Past turns must be
     * supplied in chronological order (oldest first) as ChatTurn records.
     */
    public String generateMentorReply(MentorContext ctx, List<ChatTurn> history, String latestMenteeMessage) {
        if (!isConfigured()) return "";

        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatTurn turn : history) {
            messages.add(Map.of("role", turn.role(), "content", turn.text()));
        }
        messages.add(Map.of("role", "user", "content", latestMenteeMessage));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 280);
        body.put("system", buildMentorSystemBlocks(ctx));
        body.put("messages", messages);

        return callForText(body, "mentor reply");
    }

    /**
     * Daily AI check-in message — sent by the scheduler when the mentor
     * has not pinged the mentee today. Short, specific to today's misses.
     */
    public String generateMentorCheckIn(MentorContext ctx) {
        if (!isConfigured()) return "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 220);
        body.put("system", buildMentorSystemBlocks(ctx));
        body.put("messages", List.of(
                Map.of("role", "user", "content",
                        "Daily check-in. Two sentences max. " +
                        "Reference today's progress vs. their goals and propose one tiny next step. " +
                        "Warm and direct — no filler like \"Great job!\" or \"Keep it up!\".")
        ));

        return callForText(body, "mentor check-in");
    }

    /** Performs the HTTP call and extracts the first text block. */
    private String callForText(Map<String, Object> body, String label) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> usage = (Map<?, ?>) response.getBody().get("usage");
                if (usage != null) {
                    log.debug("AI {} usage — input={} cache_create={} cache_read={} output={}",
                            label,
                            usage.get("input_tokens"),
                            usage.get("cache_creation_input_tokens"),
                            usage.get("cache_read_input_tokens"),
                            usage.get("output_tokens"));
                }
                List<?> content = (List<?>) response.getBody().get("content");
                if (content != null && !content.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) content.get(0);
                    Object text = first.get("text");
                    if (text instanceof String s) return s.strip();
                }
            }
        } catch (Exception e) {
            log.warn("AI {} generation failed: {}", label, e.getMessage());
        }
        return "";
    }

    /**
     * System prompt for the AI mentor.
     *
     * Rendered as a list with two blocks:
     *   1. Persona/instructions — tiny, fully static so it caches across all users.
     *   2. Mentee profile + stats — stable for a given mentee within the 5-min TTL.
     *
     * `cache_control` sits on the second block so everything before it (tools,
     * system block 1, system block 2) is cached. Note: prompt caching only
     * activates above ~4096 tokens on Haiku 4.5 — small habit lists won't hit
     * that floor today, but the structure keeps the door open.
     */
    private List<Map<String, Object>> buildMentorSystemBlocks(MentorContext ctx) {
        String persona = """
                You are Forma, a focused AI accountability mentor inside the user's habit-tracker app.
                Voice: warm, concrete, brief. Never use filler like "Great job!", "Keep it up!", "You've got this!".
                Always reference the mentee's actual numbers, habit names, and consistency when relevant.
                Use the HABIT TIMING block to recommend concrete times: e.g. if they consistently finish their morning run around 7am, suggest keeping that slot or pairing a nearby habit to it. Do not invent timing data that isn't in the block.
                Always end with one tiny next action they can complete in under 30 minutes.
                Reply in 1–3 short sentences unless the mentee explicitly asks for a deep breakdown.
                Never claim to be human. If asked, say you are Forma's AI mentor.
                """;

        String profile = buildMenteeContextText(ctx);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", persona));
        blocks.add(Map.of(
                "type", "text",
                "text", profile,
                "cache_control", Map.of("type", "ephemeral")
        ));
        return blocks;
    }

    private String buildMenteeContextText(MentorContext ctx) {
        String habits = ctx.habitNames().isEmpty() ? "(no habits yet)" : String.join(", ", ctx.habitNames());
        String timingBlock = ctx.habitTimingSummary() == null || ctx.habitTimingSummary().isBlank()
                ? "(not enough timestamped check-ins yet — do not guess specific times)"
                : ctx.habitTimingSummary();
        return """
                MENTEE PROFILE
                Display name: %s
                Timezone: %s
                Language: %s
                Stated goals: %s

                HABIT STATS (rolling 7-day window)
                Active habits (%d): %s
                Weekly consistency: %d%%
                Perfect days this week: %d
                Best streak: %d days
                Days of history tracked: %d
                Habits done today: %d / %d
                Missed today: %d

                HABIT TIMING (when they usually complete each habit)
                %s
                """.formatted(
                ctx.displayName(),
                ctx.timezone(),
                ctx.language(),
                ctx.goals(),
                ctx.habitNames().size(),
                habits,
                ctx.weeklyConsistencyPercent(),
                ctx.perfectDaysThisWeek(),
                ctx.bestStreak(),
                ctx.historyDays(),
                ctx.doneToday(),
                Math.max(ctx.totalHabits(), ctx.doneToday()),
                ctx.missedToday(),
                timingBlock
        );
    }

    private String buildWeeklyPrompt(String name, int pct, int perfectDays, int streak,
                                     int totalHabits, List<String> habitNames) {
        String habits = habitNames.isEmpty() ? "various habits" : String.join(", ", habitNames);
        return """
                You are writing a short, warm, personalised insight for %s's weekly habit report email.
                Stats this week: %d%% consistency, %d perfect days, %d-day best streak, %d habits tracked (%s).
                Write exactly 2 short sentences. Be specific to their numbers. Be encouraging but honest.
                Do not use filler phrases like "Great job!" or "Keep it up!". Do not use bullet points or headers.
                Output only the 2 sentences, nothing else.
                """.formatted(name, pct, perfectDays, streak, totalHabits, habits);
    }

    /// Snapshot of mentee state passed into mentor prompts.
    /// `habitTimingSummary` is a pre-formatted, one-per-line description of
    /// when the mentee typically completes each habit (e.g.
    /// `"Morning run — mornings (~7am, 12 samples)"`), used so the AI can
    /// recommend concrete times instead of generic encouragement.
    public record MentorContext(
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
            String habitTimingSummary
    ) {}

    /** One turn of mentor↔mentee chat. role is "user" (mentee) or "assistant" (mentor). */
    public record ChatTurn(String role, String text) {}
}
