package com.project.rung.service;

import com.project.rung.api.dto.SleepSnapshotRequest;
import com.project.rung.api.dto.SleepSnapshotResponse;
import com.project.rung.entity.User;
import com.project.rung.entity.UserSleepSnapshot;
import com.project.rung.repository.UserRepository;
import com.project.rung.repository.UserSleepSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Single-row-per-user sleep snapshot persistence. The iOS client owns
 * the math (Borbely two-process model + chronotype detection); this
 * service is just the storage layer that lets macOS read what iOS
 * computed.
 */
@Service
public class SleepSnapshotService {

    private final UserSleepSnapshotRepository repo;
    private final UserRepository userRepo;

    public SleepSnapshotService(UserSleepSnapshotRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    @Transactional
    public SleepSnapshotResponse upload(Long userId, SleepSnapshotRequest req) {
        UserSleepSnapshot row = repo.findByUserId(userId).orElseGet(() -> {
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            UserSleepSnapshot fresh = new UserSleepSnapshot();
            fresh.setUser(user);
            return fresh;
        });
        row.setSampleCount(req.sampleCount());
        row.setMedianWakeMinutes(req.medianWakeMinutes());
        row.setMedianBedMinutes(req.medianBedMinutes());
        row.setAverageDurationHours(req.averageDurationHours());
        row.setSleepDebtHours(req.sleepDebtHours());
        row.setMedianSleepMidpointMinutes(req.medianSleepMidpointMinutes());
        row.setMidpointIqrMinutes(req.midpointIqrMinutes());
        row.setChronotypeStable(Boolean.TRUE.equals(req.chronotypeStable()));
        row.setUpdatedAt(Instant.now());
        repo.save(row);
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public Optional<SleepSnapshotResponse> get(Long userId) {
        return repo.findByUserId(userId).map(this::toResponse);
    }

    private SleepSnapshotResponse toResponse(UserSleepSnapshot row) {
        return new SleepSnapshotResponse(
                row.getSampleCount(),
                row.getMedianWakeMinutes(),
                row.getMedianBedMinutes(),
                row.getAverageDurationHours(),
                row.getSleepDebtHours(),
                row.getMedianSleepMidpointMinutes(),
                row.getMidpointIqrMinutes(),
                row.isChronotypeStable(),
                row.getUpdatedAt()
        );
    }
}
