package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileRequest(
        @NotBlank @Size(max = 80) String displayName,
        @Size(max = 512) String avatarUrl,
        @NotBlank @Size(max = 80) String timezone,
        @NotBlank @Size(max = 16) String language,
        @NotBlank @Size(max = 300) String goals
) {
}
