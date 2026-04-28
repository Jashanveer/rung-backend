package com.project.rung.service;

import com.project.rung.service.event.AiMentorWelcomeRequestedEvent;
import com.project.rung.service.event.MenteeMessageReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AiMentorReplyListener {
    private static final Logger log = LoggerFactory.getLogger(AiMentorReplyListener.class);

    private final AccountabilityService accountabilityService;

    public AiMentorReplyListener(@Lazy AccountabilityService accountabilityService) {
        this.accountabilityService = accountabilityService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMenteeMessage(MenteeMessageReceivedEvent event) {
        try {
            accountabilityService.generateAndPersistAiMentorReply(event.matchId(), event.menteeText());
        } catch (Exception ex) {
            log.warn("AI mentor reply failed for match {}: {}", event.matchId(), ex.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAiMentorWelcome(AiMentorWelcomeRequestedEvent event) {
        try {
            accountabilityService.regenerateAiMentorWelcome(event.matchId(), event.welcomeMessageId());
        } catch (Exception ex) {
            log.warn("AI mentor welcome upgrade failed for match {}: {}", event.matchId(), ex.getMessage());
        }
    }
}
