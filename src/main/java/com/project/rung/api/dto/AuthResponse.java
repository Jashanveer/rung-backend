package com.project.rung.api.dto;

/**
 * Auth response for password login, register, refresh, and Apple sign-in.
 *
 * `isNewUser` is true exclusively on the first-time Sign in with Apple
 * path — clients use it to gate a one-time profile-setup screen
 * (username + avatar) before landing on the dashboard. Password
 * registration sets it false because the user already chose those
 * fields up-front; subsequent Apple logins also stay false because the
 * profile already exists.
 *
 * `profileSetupCompleted` mirrors the V15 server-side flag. Lets the
 * client gate the profile-setup overlay synchronously off the auth
 * response (no race with a separate /me round-trip), so a user who
 * quit mid-setup re-lands on the setup screen the moment the next
 * sign-in completes rather than briefly seeing the dashboard. Older
 * clients that don't decode this field still work via the existing
 * UserDefaults primer + /me reconcile fallback.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresAtEpochSeconds,
        boolean isNewUser,
        boolean profileSetupCompleted
) {
}
