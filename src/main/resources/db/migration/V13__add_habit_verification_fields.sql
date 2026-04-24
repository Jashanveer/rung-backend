-- ─────────────────────────────────────────────────────────────────────────────
-- V13 — Habit verification metadata + weekly-target frequency habits
-- ─────────────────────────────────────────────────────────────────────────────
-- Adds the columns needed to round-trip the client-side verification
-- pipeline (HealthKit/Screen Time suggestions) and the frequency-habit
-- feature ("gym 5×/week" drops out of today's list once the weekly count
-- hits the target).
--
-- All columns are nullable with no default — legacy rows keep working
-- exactly as before, and the client maps missing values back to the
-- existing "daily + self-report" behavior.
--
-- Tier-weighted leaderboard scoring (auto = 10×, partial = 5×, self = 1×)
-- is a follow-up — `verification_tier` on `habit_checks` captures the
-- data the scoring pass will consume when it ships.

ALTER TABLE habits
    ADD COLUMN IF NOT EXISTS canonical_key        VARCHAR(32),
    ADD COLUMN IF NOT EXISTS verification_tier    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS verification_source  VARCHAR(32),
    ADD COLUMN IF NOT EXISTS verification_param   DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS weekly_target        INTEGER;

ALTER TABLE habit_checks
    ADD COLUMN IF NOT EXISTS verification_tier    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS verification_source  VARCHAR(32);
