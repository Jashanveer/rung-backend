-- ─────────────────────────────────────────────────────────────────────────────
-- V14 — Sign in with Apple support
-- ─────────────────────────────────────────────────────────────────────────────
-- Apple's identity-token `sub` claim is the stable per-user-per-team
-- identifier for users who signed up via Apple ID. We store it on the
-- `users` row so subsequent Apple logins can find the existing record
-- (Apple only includes the user's email on the FIRST authorization;
-- after that, `sub` is the only correlation we get).
--
-- Nullable column with a unique index — password-only accounts keep
-- `apple_sub IS NULL`, while linked accounts hold the Apple sub.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS apple_sub VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_apple_sub
    ON users (apple_sub)
    WHERE apple_sub IS NOT NULL;
