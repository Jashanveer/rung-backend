package com.project.rung.service;

import com.project.rung.entity.MentorMatch;
import com.project.rung.entity.MentorMatchStatus;
import com.project.rung.repository.MentorMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nightly job that distills the last ~20 mentor-chat turns into a refreshed
 * ~100-word memory note on each mentee's profile. The AI mentor reads this
 * memory on every future call, so over weeks the chat style compounds into
 * real personalisation without any model fine-tuning.
 *
 * Runs once a day to keep total Gemini usage inside the free tier —
 * distilling after every chat turn would triple per-message API cost for
 * no meaningful gain (memory only needs to change after a few turns).
 */
@Component
public class MentorMemoryDistiller {

    private static final Logger log = LoggerFactory.getLogger(MentorMemoryDistiller.class);

    private final MentorMatchRepository matchRepo;
    private final AccountabilityService accountabilityService;
    private final MentorAI mentorAI;

    public MentorMemoryDistiller(
            MentorMatchRepository matchRepo,
            AccountabilityService accountabilityService,
            MentorAI mentorAI
    ) {
        this.matchRepo = matchRepo;
        this.accountabilityService = accountabilityService;
        this.mentorAI = mentorAI;
    }

    /// Runs at 03:00 server time every day — off-hours to keep scheduling
    /// fault zones out of the user-facing path.
    @Scheduled(cron = "0 0 3 * * *")
    public void refreshMemories() {
        if (!mentorAI.isConfigured()) return;

        List<MentorMatch> matches = matchRepo.findAllByStatus(MentorMatchStatus.ACTIVE);
        for (MentorMatch match : matches) {
            if (!match.isAiMentor()) continue;
            try {
                accountabilityService.refreshAiMentorMemory(match.getId());
            } catch (Exception ex) {
                log.warn("Memory distillation failed for match {}: {}", match.getId(), ex.getMessage());
            }
        }
    }
}
