package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UseStreakFreezeRequest(
        @NotBlank
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "dateKey must be in YYYY-MM-DD format")
        String dateKey
) {}
