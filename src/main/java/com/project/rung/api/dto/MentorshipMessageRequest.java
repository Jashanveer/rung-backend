package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MentorshipMessageRequest(
        @NotBlank @Size(max = 600) String message
) {
}
