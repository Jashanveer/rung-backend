package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Toggle payload. Verification metadata is optional; when present it
 * captures the tier/source that corroborated this specific check, which
 * the future tier-weighted leaderboard pass will consume.
 */
public record CheckUpdateRequest(
        @NotNull Boolean done,
        @Pattern(regexp = "^(auto|partial|selfReport)$", message = "verificationTier must be auto, partial, or selfReport")
        String verificationTier,
        @Size(max = 32) String verificationSource
) {
}
