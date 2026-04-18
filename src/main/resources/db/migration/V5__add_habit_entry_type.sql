ALTER TABLE habits
    ADD COLUMN IF NOT EXISTS entry_type VARCHAR(16);

UPDATE habits
SET entry_type = 'HABIT'
WHERE entry_type IS NULL;

ALTER TABLE habits
    ALTER COLUMN entry_type SET DEFAULT 'HABIT';

ALTER TABLE habits
    ALTER COLUMN entry_type SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_habits_entry_type'
    ) THEN
        ALTER TABLE habits
            ADD CONSTRAINT ck_habits_entry_type
                CHECK (entry_type IN ('HABIT', 'TASK'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_habits_user_type ON habits(user_id, entry_type);
