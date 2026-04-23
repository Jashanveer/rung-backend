-- ─────────────────────────────────────────────────────────────────────────────
-- V10 — fit encrypted ciphertext, harden duplicate-email prevention
-- ─────────────────────────────────────────────────────────────────────────────
-- AES-GCM ciphertext is wrapped as "ENC:" + Base64(12-byte IV || ct || 16-byte tag).
-- For an N-byte plaintext that's roughly 4 + ceil((N+28)/3)*4 chars:
--   254-char email -> ~376 chars   (overflowed VARCHAR(255))
--   300-char goals -> ~444 chars   (overflowed VARCHAR(255))
-- 1024 leaves headroom for both fields plus future growth.
ALTER TABLE users         ALTER COLUMN email TYPE VARCHAR(1024);
ALTER TABLE user_profiles ALTER COLUMN goals TYPE VARCHAR(1024);

-- The pre-existing UNIQUE (email) constraint is effectively a no-op once values
-- are encrypted with a random IV (every ciphertext is distinct). The hash
-- column is the real uniqueness key, so enforce it at the DB level. Concurrent
-- registrations with the same email now fail-fast on the second insert.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_hash
    ON users(email_hash);
