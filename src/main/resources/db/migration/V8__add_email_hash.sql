-- Adds a stable lookup column for encrypted email values.
-- When APP_ENCRYPTION_KEY is set the application stores HMAC-SHA256(email)
-- here and queries use this column; when encryption is disabled the column
-- is left NULL and existing email-based queries fall through to the plain
-- email column.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_hash VARCHAR(64);
