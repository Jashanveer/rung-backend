-- ─────────────────────────────────────────────────────────────────────────────
-- V15 — Profile-setup completion flag
-- ─────────────────────────────────────────────────────────────────────────────
-- Apple sign-up auto-generates a placeholder username from the email
-- local-part (e.g. "wqjhdjx4s5" for a private-relay email). The user is
-- meant to replace it on the post-signup AppleProfileSetupView. If they
-- quit mid-flow, the client previously had no way to know they hadn't
-- finished — `requiresProfileSetup` lived only in memory, so on cold
-- launch the user landed on the dashboard with the placeholder as
-- their permanent handle.
--
-- This column is the server-side source of truth: false until the user
-- successfully POSTs `/api/users/me/setup-profile`, then true forever.
-- The client reads it from `MeResponse` after every cold launch and
-- re-shows the setup overlay if false.
--
-- Backfill: every existing row pre-dates this flag and is past setup
-- (either a password account that picked its own username at registration
-- or an Apple account that's been around long enough to have run
-- setup-profile). Defaulting them all to TRUE is correct.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS profile_setup_completed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users SET profile_setup_completed = TRUE;
