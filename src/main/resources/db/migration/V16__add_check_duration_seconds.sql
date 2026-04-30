-- Tracks how long the user spent completing the habit on this check.
-- Populated when a check is completed via Focus Mode (which knows the
-- session length); historic / manual-toggle rows stay NULL. Combined with
-- the existing `completed_at` column, this lets the app:
--   * surface a "median time of day" hint per habit
--   * show a speed-improvement trend over time
ALTER TABLE habit_checks
    ADD COLUMN duration_seconds INTEGER;
