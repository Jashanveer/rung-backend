package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Habit mutation payload. Verification fields are optional — null means
 * "leave unchanged" rather than "clear" (callers that want to clear must
 * send the sentinel the server defines for their context, or use a future
 * dedicated endpoint; the client does not currently need this path).
 */
public record HabitUpdateRequest(
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
