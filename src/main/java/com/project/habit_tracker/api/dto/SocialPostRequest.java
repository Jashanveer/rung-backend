package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialPostRequest(
        @NotBlank @Size(max = 500) String message
) {
}
