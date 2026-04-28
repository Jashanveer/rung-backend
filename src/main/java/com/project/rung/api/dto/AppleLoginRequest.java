package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sign in with Apple credential. Clients post the Apple-issued
 * identityToken (a JWS signed by Apple's RSA keys) — the server verifies
 * the signature against `https://appleid.apple.com/auth/keys`, validates
 * the issuer/audience claims, and either looks up an existing user by
 * `sub` or provisions a new account from the embedded `email`.
 *
 * `displayName` is optional — Apple only returns the user's full name on
 * the FIRST sign-in. Clients send it through to seed the UserProfile
 * row when present; subsequent logins just send `identityToken`.
 */
public record AppleLoginRequest(
        @NotBlank String identityToken,
        String displayName
) {
}
