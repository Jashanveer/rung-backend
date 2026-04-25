package com.project.habit_tracker.api.dto;

/**
 * Auth response for password login, register, refresh, and Apple sign-in.
 *
 * `isNewUser` is true exclusively on the first-time Sign in with Apple
 * path — clients use it to gate a one-time profile-setup screen
 * (username + avatar) before landing on the dashboard. Password
 * registration sets it false because the user already chose those
 * fields up-front; subsequent Apple logins also stay false because the
 * profile already exists.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresAtEpochSeconds,
        boolean isNewUser
) {
}
