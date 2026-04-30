-- Per-user sleep snapshot synced up by the iOS client (HealthKit is
-- unavailable on native macOS apps; the Mac client reads this row to
-- power its energy view instead of trying to query HealthKit locally).
--
-- One row per user: the snapshot is overwritten on every successful
-- iOS-side refresh, so we never grow the table beyond N users. Nullable
-- midpoint columns mirror the Swift snapshot — a user with too few
-- nights tracked still has a row, just with nulls in the chronotype
-- fields.
CREATE TABLE user_sleep_snapshots (
    user_id                       BIGINT       PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    sample_count                  INTEGER      NOT NULL,
    median_wake_minutes           INTEGER      NOT NULL,
    median_bed_minutes            INTEGER      NOT NULL,
    average_duration_hours        DOUBLE PRECISION NOT NULL,
    sleep_debt_hours              DOUBLE PRECISION NOT NULL,
    median_sleep_midpoint_minutes INTEGER,
    midpoint_iqr_minutes          INTEGER      NOT NULL DEFAULT 0,
    chronotype_stable             BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
