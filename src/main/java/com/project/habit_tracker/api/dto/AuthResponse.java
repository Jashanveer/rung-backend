package com.project.habit_tracker.api.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresAtEpochSeconds
) {
}
