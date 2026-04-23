package com.project.habit_tracker.config;

import com.project.habit_tracker.service.AIService;
import com.project.habit_tracker.service.GeminiMentorAI;
import com.project.habit_tracker.service.MentorAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Picks which {@link MentorAI} implementation is injected into callers that
 * depend on the interface — driven by the `mentor.provider` property.
 *
 *   mentor.provider=gemini    → GeminiMentorAI (default)
 *   mentor.provider=anthropic → AIService (legacy Claude path)
 *
 * Both service beans are always instantiated so the app can switch providers
 * at runtime by flipping the property and restarting — no recompile.
 */
@Configuration
public class MentorAIConfig {

    private static final Logger log = LoggerFactory.getLogger(MentorAIConfig.class);

    @Bean
    @Primary
    public MentorAI mentorAI(
            @Value("${mentor.provider:gemini}") String provider,
            AIService anthropicService,
            GeminiMentorAI geminiService
    ) {
        boolean useAnthropic = "anthropic".equalsIgnoreCase(provider);
        MentorAI chosen = useAnthropic ? anthropicService : geminiService;
        log.info("MentorAI provider = {} (configured={})",
                useAnthropic ? "anthropic" : "gemini",
                chosen.isConfigured());
        return chosen;
    }
}
