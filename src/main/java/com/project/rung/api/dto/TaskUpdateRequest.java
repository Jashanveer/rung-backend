package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
        @NotBlank @Size(max = 120) String title
) {
}
