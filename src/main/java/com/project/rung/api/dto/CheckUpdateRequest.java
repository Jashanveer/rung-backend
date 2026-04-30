package com.project.rung.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Toggle payload. Verification metadata is optional; when present it
 * captures the tier/source that corroborated this specific check, which
 * the future tier-weighted leaderboard pass will consume.
 *
 * {@code durationSeconds} is only set when the client knows how long the
 * habit took (currently: a Focus-Mode session that ended on this check).
 * Capped at 12 hours to reject obvious instrumentation glitches.
 */
public record CheckUpdateRequest(
        @NotNull Boolean done,
        @Pattern(regexp = "^(auto|partial|selfReport)$", message = "verificationTier must be auto, partial, or selfReport")
        String verificationTier,
        @Size(max = 32) String verificationSource,
        @Min(0) @Max(12 * 60 * 60) Integer durationSeconds
) {
}
