-- ─────────────────────────────────────────────────────────────────────────────
-- V1 — full baseline schema
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    username      VARCHAR(40),
    password_hash VARCHAR(255) NOT NULL,
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE user_profiles (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL UNIQUE REFERENCES users(id),
    display_name VARCHAR(255) NOT NULL,
    avatar_url   VARCHAR(512),
    timezone     VARCHAR(255) NOT NULL,
    language     VARCHAR(16)  NOT NULL,
    goals        VARCHAR(255) NOT NULL
);

CREATE TABLE habits (
    id      BIGSERIAL    PRIMARY KEY,
    user_id BIGINT       NOT NULL REFERENCES users(id),
    title   VARCHAR(255) NOT NULL
);

CREATE TABLE habit_checks (
    id           BIGSERIAL   PRIMARY KEY,
    habit_id     BIGINT      NOT NULL REFERENCES habits(id),
    date_key     VARCHAR(10) NOT NULL,
    done         BOOLEAN     NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_habit_checks_habit_date UNIQUE (habit_id, date_key)
);

CREATE TABLE mentor_matches (
    id            BIGSERIAL    PRIMARY KEY,
    mentor_id     BIGINT       NOT NULL REFERENCES users(id),
    mentee_id     BIGINT       NOT NULL REFERENCES users(id),
    status        VARCHAR(32)  NOT NULL,
    match_score   INT          NOT NULL,
    match_reasons VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    ended_at      TIMESTAMPTZ
);

CREATE TABLE mentorship_messages (
    id         BIGSERIAL    PRIMARY KEY,
    match_id   BIGINT       NOT NULL REFERENCES mentor_matches(id),
    sender_id  BIGINT       NOT NULL REFERENCES users(id),
    message    VARCHAR(600) NOT NULL,
    nudge      BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE social_posts (
    id         BIGSERIAL    PRIMARY KEY,
    author_id  BIGINT       NOT NULL REFERENCES users(id),
    message    VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE friend_connections (
    id           BIGSERIAL   PRIMARY KEY,
    requester_id BIGINT      NOT NULL REFERENCES users(id),
    addressee_id BIGINT      NOT NULL REFERENCES users(id),
    status       VARCHAR(24) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_friend_connections UNIQUE (requester_id, addressee_id)
);

CREATE INDEX idx_friend_requester_status ON friend_connections(requester_id, status);
CREATE INDEX idx_friend_addressee_status ON friend_connections(addressee_id, status);

CREATE TABLE device_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    token      VARCHAR(200) NOT NULL UNIQUE,
    platform   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE streak_freezes (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users(id),
    granted_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    used_for_date_key  VARCHAR(10)
);

CREATE TABLE reward_grants (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    habit_id   BIGINT      NOT NULL REFERENCES habits(id),
    date_key   VARCHAR(10) NOT NULL,
    xp_granted INT         NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_reward_grant_user_habit_date UNIQUE (user_id, habit_id, date_key)
);

CREATE TABLE email_verification_codes (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(320) NOT NULL,
    code       VARCHAR(6)   NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ
);

CREATE INDEX idx_email_verification_email ON email_verification_codes(email);

CREATE TABLE password_reset_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    token      VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
