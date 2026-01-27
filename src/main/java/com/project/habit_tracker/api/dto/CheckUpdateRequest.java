package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotNull;

public record CheckUpdateRequest(
        @NotNull Boolean done
) {
}