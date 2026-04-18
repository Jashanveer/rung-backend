package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TaskCreateRequest(
        @NotBlank String title
) {
}
