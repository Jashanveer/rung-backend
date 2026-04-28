package com.project.rung.api.dto;

import jakarta.validation.constraints.NotNull;

public record UserPreferencesRequest(
        @NotNull Boolean emailOptIn
) {
}
