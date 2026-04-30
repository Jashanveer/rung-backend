package com.project.rung.api.dto;

import java.time.Instant;

/**
 * Response for {@code GET /api/sleep/snapshot}. Mirrors the iOS client's
 * SleepSnapshot shape so the Mac client can decode straight into its own
 * EnergyForecast pipeline.
 *
 * {@code updatedAt} lets the Mac decide whether to dim the readout
 * ("synced 3 days ago — open iPhone") or treat it as live.
 */
public record SleepSnapshotResponse(
        int sampleCount,
        int medianWakeMinutes,
        int medianBedMinutes,
        double averageDurationHours,
        double sleepDebtHours,
        Integer medianSleepMidpointMinutes,
        int midpointIqrMinutes,
        boolean chronotypeStable,
        Instant updatedAt
) {}
