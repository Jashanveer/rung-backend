package com.project.habit_tracker.service;

import com.project.habit_tracker.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private final RefreshTokenRepository refreshTokenRepo;

    public RefreshTokenCleanupScheduler(RefreshTokenRepository refreshTokenRepo) {
        this.refreshTokenRepo = refreshTokenRepo;
    }

    /** Runs nightly at 03:00. Removes expired and already-used refresh tokens. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeStaleTokens() {
        int deleted = refreshTokenRepo.deleteExpiredAndUsed(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired/used refresh tokens", deleted);
        }
    }
}
