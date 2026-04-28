package com.project.rung.config;

import com.project.rung.service.AIService;
import com.project.rung.service.GeminiMentorAI;
import com.project.rung.service.MentorAI;
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
 *   mentor.provider=anthropic → AIService (default — Claude Haiku 4.5)
 *   mentor.provider=gemini    → GeminiMentorAI (cold-swap escape hatch)
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
            @Value("${mentor.provider:anthropic}") String provider,
            AIService anthropicService,
            GeminiMentorAI geminiService
    ) {
        boolean useGemini = "gemini".equalsIgnoreCase(provider);
        MentorAI chosen = useGemini ? geminiService : anthropicService;
        log.info("MentorAI provider = {} (configured={})",
                useGemini ? "gemini" : "anthropic",
                chosen.isConfigured());
        return chosen;
    }
}
