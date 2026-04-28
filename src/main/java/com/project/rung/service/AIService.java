package com.project.rung.service;

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
 * Anthropic (Claude) implementation of {@link MentorAI}. Default and only
 * production provider. The Gemini path remains in the codebase as a
 * cold-swap escape hatch, but {@code mentor.provider=anthropic} (set in
 * application.properties) routes every mentor call through here.
 *
 * Uses RestTemplate directly — no Anthropic SDK dependency. Mentor system
 * prompts are built as a list with `cache_control` so the per-mentee
 * profile prefix is reused across turns inside the 5-minute TTL.
 *
 * Model is env-configurable so the user can swap up to Sonnet 4.6 if the
 * default Haiku 4.5 ever drifts off persona — without a code change.
 */
@Service
public class AIService implements MentorAI {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final RestTemplate restTemplate;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    // Default Haiku 4.5 — cheapest current Claude, dramatically better than
    // prior Haiku generations at holding a persona on short outputs. With
    // ~1.5K input + 200 output tokens per mentor turn this is roughly
    // $0.0025/turn — about 2000 turns per $5 of credit. Override with
    // ANTHROPIC_MODEL=claude-sonnet-4-6 in .env if persona quality matters
    // more than cost on a given account.
    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    public AIService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String generateWeeklyInsight(String displayName, int consistencyPct, int perfectDays,
                                         int bestStreak, int totalHabits, List<String> habitNames,
                                         MentorPersonality personality) {
        if (!isConfigured()) {
            log.debug("Anthropic API key not set — skipping AI insight");
            return "";
        }

        String prompt = buildWeeklyPrompt(displayName, consistencyPct, perfectDays, bestStreak, totalHabits, habitNames, personality);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 220,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return callForText(body, "weekly insight");
    }

    @Override
    public String generateMentorWelcome(MentorContext ctx, MentorPersonality personality, String memory) {
        if (!isConfigured()) return "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 220);
        body.put("system", buildMentorSystemBlocks(ctx, personality, memory));
        body.put("messages", List.of(
                Map.of("role", "user", "content",
                        "Send the very first welcome message to your new mentee. " +
                        "Two short sentences. Reference one specific thing about their habits or recent week. " +
                        "End with one tiny action they can take in the next hour.")
        ));

        return callForText(body, "mentor welcome");
    }

    @Override
    public String generateMentorReply(MentorContext ctx, MentorPersonality personality, String memory,
                                      List<ChatTurn> history, String latestMenteeMessage) {
        if (!isConfigured()) return "";

        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatTurn turn : history) {
            messages.add(Map.of("role", turn.role(), "content", turn.text()));
        }
        messages.add(Map.of("role", "user", "content", latestMenteeMessage));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 280);
        body.put("system", buildMentorSystemBlocks(ctx, personality, memory));
        body.put("messages", messages);

        return callForText(body, "mentor reply");
    }

    @Override
    public String generatePeriodicCheckIn(MentorContext ctx, MentorPersonality personality, String memory, CheckInWindow window) {
        if (!isConfigured()) return "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 220);
        body.put("system", buildMentorSystemBlocks(ctx, personality, memory));
        body.put("messages", List.of(
                Map.of("role", "user", "content", window.promptHint() + " Two sentences max. Warm and direct — no filler.")
        ));

        return callForText(body, "periodic check-in " + window.name().toLowerCase());
    }

    @Override
    public String generateOverloadNudge(MentorContext ctx, MentorPersonality personality, String memory, OverloadAssessment assessment) {
        if (!isConfigured()) return "";

        String weakest = assessment.weakestHabitNames().isEmpty()
                ? "(none surfaced)"
                : String.join(", ", assessment.weakestHabitNames());
        String userPrompt = """
                The mentee looks overloaded — %d active habits at only %d%% weekly consistency and %d missed today.
                Weakest habits right now: %s.
                Write a 1–2 sentence in-character message that gently suggests dropping or pausing ONE habit so the rest stick.
                Name the habit you recommend pausing. Do not moralise.
                """.formatted(
                        assessment.totalHabits(),
                        assessment.weeklyConsistencyPercent(),
                        assessment.missedTodayCount(),
                        weakest
                );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 200);
        body.put("system", buildMentorSystemBlocks(ctx, personality, memory));
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        return callForText(body, "overload nudge");
    }

    @Override
    public String distillMemory(MentorContext ctx, List<ChatTurn> recentHistory, String previousMemory) {
        if (!isConfigured()) return previousMemory == null ? "" : previousMemory;

        StringBuilder transcript = new StringBuilder();
        for (ChatTurn turn : recentHistory) {
            String who = "user".equals(turn.role()) ? "Mentee" : "Mentor";
            transcript.append(who).append(": ").append(turn.text()).append('\n');
        }
        String prev = previousMemory == null ? "(none)" : previousMemory.isBlank() ? "(none)" : previousMemory;
        String prompt = """
                Maintain a third-person memory note about this mentee for future AI mentor turns.
                PREVIOUS MEMORY:
                %s

                RECENT CONVERSATION (oldest first):
                %s

                Return an updated memory in 80–100 words. Include: mood patterns, sticking points,
                wins worth repeating, preferred cadence, and any stated constraint the mentor should
                honour. Do NOT include today's stats (those are already in the profile block).
                Output the memory only — no preamble, no labels.
                """.formatted(prev, transcript.toString().strip());

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 220,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        String out = callForText(body, "memory distillation");
        return out.isBlank() ? (previousMemory == null ? "" : previousMemory) : out;
    }

    // MARK: — HTTP + prompt helpers

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

    private List<Map<String, Object>> buildMentorSystemBlocks(MentorContext ctx, MentorPersonality personality, String memory) {
        String persona = personalityPersona(personality);
        String profile = buildMenteeContextText(ctx);
        String memoryBlock = (memory == null || memory.isBlank())
                ? "MEMORY\n(no prior memory yet)"
                : "MEMORY (carry-forward notes on this mentee)\n" + memory.strip();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", persona));
        blocks.add(Map.of(
                "type", "text",
                "text", profile + "\n\n" + memoryBlock,
                "cache_control", Map.of("type", "ephemeral")
        ));
        return blocks;
    }

    private String personalityPersona(MentorPersonality personality) {
        MentorPersonality p = personality == null ? MentorPersonality.COACH : personality;
        return """
                You are Rung, an AI accountability mentor inside the user's rung app.
                %s
                Always reference the mentee's actual numbers, habit names, and consistency when relevant.
                Use the HABIT TIMING block to recommend concrete times; do not invent timing data that isn't there.
                End with one tiny next action the mentee can complete in under 30 minutes.
                Keep replies to 1–3 short sentences unless the mentee explicitly asks for a deep breakdown.
                Never claim to be human. If asked, say you are Rung's AI mentor.
                """.formatted(p.systemPromptBlock().strip());
    }

    private String buildMenteeContextText(MentorContext ctx) {
        String habits = ctx.habitNames().isEmpty() ? "(no habits yet)" : String.join(", ", ctx.habitNames());
        String timingBlock = ctx.habitTimingSummary() == null || ctx.habitTimingSummary().isBlank()
                ? "(not enough timestamped check-ins yet — do not guess specific times)"
                : ctx.habitTimingSummary();
        String weeklyTargetBlock = ctx.weeklyTargetSummary() == null || ctx.weeklyTargetSummary().isBlank()
                ? "(no frequency-based habits)"
                : ctx.weeklyTargetSummary();
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
                Verified score this week: %d (auto×10 + partial×5 + self×1)

                WEEKLY-TARGET HABITS (N× per ISO week)
                %s

                HABIT TIMING (when they usually complete each habit)
                %s
                """.formatted(
                ctx.safeDisplayName(),
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
                ctx.verifiedScore(),
                weeklyTargetBlock,
                timingBlock
        );
    }

    private String buildWeeklyPrompt(String name, int pct, int perfectDays, int streak,
                                     int totalHabits, List<String> habitNames, MentorPersonality personality) {
        String habits = habitNames.isEmpty() ? "various habits" : String.join(", ", habitNames);
        String voiceHint = personality == null ? "" : personality.systemPromptBlock().strip() + "\n";
        return """
                %sYou are writing a short, warm, personalised insight for %s's weekly habit report email.
                Stats this week: %d%% consistency, %d perfect days, %d-day best streak, %d habits tracked (%s).
                Write exactly 2 short sentences. Be specific to their numbers. Be encouraging but honest.
                Do not use filler phrases like "Great job!" or "Keep it up!". Do not use bullet points or headers.
                Output only the 2 sentences, nothing else.
                """.formatted(voiceHint, name, pct, perfectDays, streak, totalHabits, habits);
    }
}
