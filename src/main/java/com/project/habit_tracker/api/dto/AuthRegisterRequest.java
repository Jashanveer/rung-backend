package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthRegisterRequest(
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "^[A-Za-z0-9_]+$") String username,
        @Email @NotBlank String email,
        @Size(min = 8, max = 100) String password,
        @Size(max = 512) String avatarUrl
) {
}
