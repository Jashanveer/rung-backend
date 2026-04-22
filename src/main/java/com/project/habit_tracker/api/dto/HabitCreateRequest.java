package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HabitCreateRequest(
        @NotBlank @Size(max = 120) String title,
        @Pattern(regexp = "^(Morning|Afternoon|Evening)$", message = "reminderWindow must be Morning, Afternoon, or Evening")
        String reminderWindow
) {
}
