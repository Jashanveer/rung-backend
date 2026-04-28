package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthRefreshRequest(
        @NotBlank String refreshToken
) {
}
