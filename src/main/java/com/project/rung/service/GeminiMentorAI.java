package com.project.rung.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini implementation of {@link MentorAI}. Default provider — set
 * {@code mentor.provider=anthropic} in .env to fall back to {@link AIService}.
 *
 * Uses the free-tier Gemini Developer API (Studio key):
 *   POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *
 * Auth is a single `x-goog-api-key` header. No SDK dependency — matches the
 * Anthropic path's RestTemplate style so both providers behave identically
 * from the caller's perspective.
 */
@Service
public class GeminiMentorAI implements MentorAI {

    private static final Logger log = LoggerFactory.getLogger(GeminiMentorAI.class);
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestTemplate restTemplate;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String model;

    public GeminiMentorAI(RestTemplate restTemplate) {
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
            log.debug("Gemini API key not set — skipping weekly insight");
            return "";
        }
        String habits = habitNames.isEmpty() ? "various habits" : String.join(", ", habitNames);
        String voiceHint = personality == null ? "" : personality.systemPromptBlock().strip() + "\n";
        String prompt = """
                %sYou are writing a short, warm, personalised insight for %s's weekly habit report email.
                Stats this week: %d%% consistency, %d perfect days, %d-day best streak, %d habits tracked (%s).
                Write exactly 2 short sentences. Be specific to their numbers. Be encouraging but honest.
                Do not use filler phrases like "Great job!" or "Keep it up!". Do not use bullet points or headers.
                Output only the 2 sentences, nothing else.
                """.formatted(voiceHint, displayName, consistencyPct, perfectDays, bestStreak, totalHabits, habits);

        return generate(
                /* systemInstruction */ null,
                List.of(userTurn(prompt)),
                220,
                0.6,
                "weekly insight"
        );
    }

    @Override
    public String generateMentorWelcome(MentorContext ctx, MentorPersonality personality, String memory) {
        if (!isConfigured()) return "";
        String user = "Send the very first welcome message to your new mentee. " +
                "Two short sentences. Reference one specific thing about their habits or recent week. " +
                "End with one tiny action they can take in the next hour.";
        return generate(buildSystemInstruction(ctx, personality, memory),
                List.of(userTurn(user)),
                220, 0.8, "mentor welcome");
    }

    @Override
    public String generateMentorReply(MentorContext ctx, MentorPersonality personality, String memory,
                                      List<ChatTurn> history, String latestMenteeMessage) {
        if (!isConfigured()) return "";
        List<Map<String, Object>> contents = new ArrayList<>(history.size() + 1);
        for (ChatTurn t : history) {
            // Gemini uses "user" for mentee turns and "model" for assistant turns.
            String role = "user".equals(t.role()) ? "user" : "model";
            contents.add(turn(role, t.text()));
        }
        contents.add(userTurn(latestMenteeMessage));
        return generate(buildSystemInstruction(ctx, personality, memory), contents,
                280, 0.8, "mentor reply");
    }

    @Override
    public String generatePeriodicCheckIn(MentorContext ctx, MentorPersonality personality, String memory, CheckInWindow window) {
        if (!isConfigured()) return "";
        String user = window.promptHint() + " Two sentences max. Warm and direct — no filler.";
        return generate(buildSystemInstruction(ctx, personality, memory),
                List.of(userTurn(user)),
                220, 0.75, "check-in " + window.name().toLowerCase());
    }

    @Override
    public String generateOverloadNudge(MentorContext ctx, MentorPersonality personality, String memory, OverloadAssessment assessment) {
        if (!isConfigured()) return "";
        String weakest = assessment.weakestHabitNames().isEmpty()
                ? "(none surfaced)"
                : String.join(", ", assessment.weakestHabitNames());
        String user = """
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
        return generate(buildSystemInstruction(ctx, personality, memory),
                List.of(userTurn(user)),
                200, 0.75, "overload nudge");
    }

    @Override
    public String distillMemory(MentorContext ctx, List<ChatTurn> recentHistory, String previousMemory) {
        if (!isConfigured()) return previousMemory == null ? "" : previousMemory;

        StringBuilder transcript = new StringBuilder();
        for (ChatTurn t : recentHistory) {
            String who = "user".equals(t.role()) ? "Mentee" : "Mentor";
            transcript.append(who).append(": ").append(t.text()).append('\n');
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

        String out = generate(null, List.of(userTurn(prompt)), 220, 0.4, "memory distillation");
        return out.isBlank() ? (previousMemory == null ? "" : previousMemory) : out;
    }

    // MARK: — prompt + HTTP plumbing

    private Map<String, Object> buildSystemInstruction(MentorContext ctx, MentorPersonality personality, String memory) {
        String persona = personalityPersona(personality);
        String profile = buildMenteeContextText(ctx);
        String memoryBlock = (memory == null || memory.isBlank())
                ? "MEMORY\n(no prior memory yet)"
                : "MEMORY (carry-forward notes on this mentee)\n" + memory.strip();
        String fullSystem = persona + "\n\n" + profile + "\n\n" + memoryBlock;
        return Map.of("parts", List.of(Map.of("text", fullSystem)));
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

    private Map<String, Object> turn(String role, String text) {
        return Map.of("role", role, "parts", List.of(Map.of("text", text)));
    }

    private Map<String, Object> userTurn(String text) { return turn("user", text); }

    /**
     * Core Gemini call. `systemInstruction` may be null (weekly insight +
     * memory distillation don't need the full mentor context carried in).
     */
    @SuppressWarnings("rawtypes")
    private String generate(Map<String, Object> systemInstruction,
                            List<Map<String, Object>> contents,
                            int maxOutputTokens,
                            double temperature,
                            String label) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (systemInstruction != null) {
                body.put("system_instruction", systemInstruction);
            }
            body.put("contents", contents);
            body.put("generationConfig", Map.of(
                    "maxOutputTokens", maxOutputTokens,
                    "temperature", temperature
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            URI uri = URI.create(API_BASE + model + ":generateContent");
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(uri, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Gemini {} returned {}", label, response.getStatusCode());
                return "";
            }

            Map<?, ?> usage = (Map<?, ?>) response.getBody().get("usageMetadata");
            if (usage != null) {
                log.debug("Gemini {} usage — prompt={} candidates={} total={}",
                        label,
                        usage.get("promptTokenCount"),
                        usage.get("candidatesTokenCount"),
                        usage.get("totalTokenCount"));
            }

            List<?> candidates = (List<?>) response.getBody().get("candidates");
            if (candidates == null || candidates.isEmpty()) return "";
            Map<?, ?> first = (Map<?, ?>) candidates.get(0);
            Map<?, ?> content = (Map<?, ?>) first.get("content");
            if (content == null) return "";
            List<?> parts = (List<?>) content.get("parts");
            if (parts == null || parts.isEmpty()) return "";
            Object text = ((Map<?, ?>) parts.get(0)).get("text");
            return text instanceof String s ? s.strip() : "";
        } catch (Exception e) {
            log.warn("Gemini {} generation failed: {}", label, e.getMessage());
            return "";
        }
    }
}
