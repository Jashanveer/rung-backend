package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TaskUpdateRequest(
        @NotBlank String title
) {
}
