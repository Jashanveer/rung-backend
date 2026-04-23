package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthLoginRequest(
        @NotBlank @Size(max = 254) String username,
        @NotBlank @Size(max = 100) String password
) {
}
