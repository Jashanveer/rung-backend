-- ─────────────────────────────────────────────────────────────────────────────
-- V11 — per-user email preferences
-- ─────────────────────────────────────────────────────────────────────────────
-- Today the only scheduled email is the Sunday weekly report. Stored as a
-- single boolean rather than per-channel so the UI stays a one-toggle tile.
-- Default TRUE preserves the historical opt-in state for existing users.
ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS email_opt_in BOOLEAN NOT NULL DEFAULT TRUE;
