-- ─────────────────────────────────────────────────────────────────────────────
-- V7 — AI mentor fallback
--   • Add ai_mentor flag to mentor_matches so backend can route messages
--   • Seed a dedicated "Forma AI Mentor" user so MentorMatch.mentor_id stays
--     a real FK (frontend renders identical chat — only the badge differs).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE mentor_matches
    ADD COLUMN IF NOT EXISTS ai_mentor BOOLEAN NOT NULL DEFAULT FALSE;

-- Sentinel password hash that never matches BCrypt-encoded user passwords,
-- so the AI mentor account cannot be logged into.
INSERT INTO users (email, username, password_hash)
SELECT 'ai-mentor@forma.app', 'forma_ai_mentor', '!!AI_MENTOR_NO_LOGIN!!'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'ai-mentor@forma.app');

INSERT INTO user_profiles (user_id, display_name, avatar_url, timezone, language, goals, mentor_rating)
SELECT u.id, 'Forma AI Mentor', NULL, 'UTC', 'EN',
       'Personalised tips, daily check-ins, motivation', 100
FROM users u
WHERE u.email = 'ai-mentor@forma.app'
  AND NOT EXISTS (SELECT 1 FROM user_profiles p WHERE p.user_id = u.id);
