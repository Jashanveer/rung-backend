package com.project.habit_tracker.api.dto;

import jakarta.validation.constraints.NotBlank;

public record DeviceTokenRequest(
        @NotBlank String token,
        @NotBlank String platform
) {}
