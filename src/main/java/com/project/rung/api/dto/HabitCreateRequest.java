package com.project.rung.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Habit creation payload. Verification metadata and weekly target are
 * optional — pre-verification clients keep sending {title, reminderWindow}
 * and the server persists nulls for the new fields, matching legacy
 * behavior. The client always stores these fields locally; round-tripping
 * them through the API is what keeps multi-device accounts consistent.
 */
public record HabitCreateRequest(
        @NotBlank @Size(max = 120) String title,
        @Pattern(regexp = "^(Morning|Afternoon|Evening)$", message = "reminderWindow must be Morning, Afternoon, or Evening")
        String reminderWindow,
        @Size(max = 32) String canonicalKey,
        @Pattern(regexp = "^(auto|partial|selfReport)$", message = "verificationTier must be auto, partial, or selfReport")
        String verificationTier,
        @Size(max = 32) String verificationSource,
        Double verificationParam,
        @Min(1) @Max(7) Integer weeklyTarget
) {
}
