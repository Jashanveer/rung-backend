-- Branding: the AI accountability mentor is named "Bruce" in the product UI
-- (matches the walking character art in the Forma client). Rename the seeded
-- profile so the backend is the single source of truth for the display name
-- and the iOS client no longer needs a hardcoded fallback.
UPDATE user_profiles
SET display_name = 'Bruce'
WHERE user_id IN (SELECT id FROM users WHERE email = 'ai-mentor@forma.app');
