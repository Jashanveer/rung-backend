package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One-time post-Sign-in-with-Apple setup payload. Client posts this
 * after the user picks their public username and avatar on the
 * profile-setup screen that follows their first Apple authorization.
 *
 * Username rules match the existing register flow — 3-30 alphanumeric +
 * underscore — so a user who switches from Apple-first to email-based
 * recovery later doesn't have to rename. Uniqueness is checked on the
 * server; clients get a 4xx with a recognizable message on collision.
 *
 * `avatarUrl` must point at the same DiceBear-style URL the existing
 * AvatarChoice grid produces, validated server-side via
 * `normalizeAvatarUrl` to prevent clients from injecting arbitrary URLs.
 *
 * `displayName` is the user's real name — required for Apple sign-ups
 * where Apple's identity token didn't carry `fullName` (e.g. private-
 * relay sign-ins after the first authorization). Optional in edit mode
 * so a rename doesn't have to also change the display name.
 */
public record ProfileSetupRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_]{3,30}$",
                message = "Username must be 3-30 letters, numbers, or underscores")
        String username,
        @Size(max = 512) String avatarUrl,
        @Size(max = 100) String displayName
) {
}
