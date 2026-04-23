package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotNull;

public record UserPreferencesRequest(
        @NotNull Boolean emailOptIn
) {
}
