-- ─────────────────────────────────────────────────────────────────────────────
-- V12 — Rotating AI mentor personality + per-user memory
-- ─────────────────────────────────────────────────────────────────────────────
-- Every 7 days the user's AI mentor rotates to a new random personality
-- (COACH / CHEERLEADER / SAGE / FRIEND) — done lazily on the next AI call
-- via PersonalityRotator, so there's no extra scheduler to maintain.
--
-- mentor_memory is a ~100-word third-person summary of the mentee distilled
-- from each chat session's closing turn. It's injected into every future
-- system prompt so the AI "remembers" sticking points, mood patterns, and
-- stated constraints without needing fine-tuning.

ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS mentor_personality VARCHAR(24),
    ADD COLUMN IF NOT EXISTS mentor_personality_assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS mentor_memory TEXT;
