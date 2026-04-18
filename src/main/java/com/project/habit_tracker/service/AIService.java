package com.project.habit_tracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API to generate personalised weekly insight text.
 * Uses RestTemplate directly — no Anthropic SDK dependency required.
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

    /**
     * Returns a 2–3 sentence personalised insight paragraph for the weekly email.
     * Falls back to an empty string if the API key is absent or the call fails.
     */
    public String generateWeeklyInsight(String displayName, int consistencyPct, int perfectDays,
                                         int bestStreak, int totalHabits, List<String> habitNames) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Anthropic API key not set — skipping AI insight");
            return "";
        }

        String prompt = buildPrompt(displayName, consistencyPct, perfectDays, bestStreak, totalHabits, habitNames);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "max_tokens", 200,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<?> content = (List<?>) response.getBody().get("content");
                if (content != null && !content.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) content.get(0);
                    Object text = first.get("text");
                    if (text instanceof String s) return s.strip();
                }
            }
        } catch (Exception e) {
            log.warn("AI insight generation failed: {}", e.getMessage());
        }
        return "";
    }

    private String buildPrompt(String name, int pct, int perfectDays, int streak,
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
}
