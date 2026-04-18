package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HabitUpdateRequest(
        @NotBlank String title,
        @Pattern(regexp = "^(Morning|Afternoon|Evening)$", message = "reminderWindow must be Morning, Afternoon, or Evening")
        String reminderWindow
) {
}
