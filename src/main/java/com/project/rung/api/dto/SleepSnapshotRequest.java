package com.project.rung.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code POST /api/sleep/snapshot}. The iOS client pushes the
 * snapshot it just computed locally; the Mac client reads the same shape
 * back from {@code GET /api/sleep/snapshot}.
 *
 * Bounds are forgiving but defensive — we accept up to 30 nights of
 * data (the rolling window the iOS service uses), wake/bed minutes in
 * 0..1439, and reject any sleep-debt figure beyond 200h (any larger
 * is a clock or unit bug, not real sleep deprivation).
 */
public record SleepSnapshotRequest(
        @NotNull @Min(0) @Max(60) Integer sampleCount,
        @NotNull @Min(0) @Max(1439) Integer medianWakeMinutes,
        @NotNull @Min(0) @Max(1439) Integer medianBedMinutes,
        @NotNull @Min(0) @Max(24) Double averageDurationHours,
        @NotNull @Min(0) @Max(200) Double sleepDebtHours,
        @Min(0) @Max(1439) Integer medianSleepMidpointMinutes,
        @NotNull @Min(0) @Max(720) Integer midpointIqrMinutes,
        @NotNull Boolean chronotypeStable
) {}
