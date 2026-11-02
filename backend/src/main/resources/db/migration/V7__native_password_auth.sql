ALTER TABLE stog_user
    ADD COLUMN display_name VARCHAR(40) NULL,
    ADD CONSTRAINT stog_user_display_name_valid CHECK (
        display_name IS NULL OR character_length(display_name) BETWEEN 1 AND 40
    );

CREATE TABLE auth_password_credential (
    user_id UUID PRIMARY KEY REFERENCES stog_user(id) ON DELETE RESTRICT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_password_username_unique UNIQUE (username),
    CONSTRAINT auth_password_username_valid CHECK (
        username ~ '^[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]$'
    ),
    CONSTRAINT auth_password_hash_valid CHECK (
        character_length(password_hash) BETWEEN 1 AND 255
    ),
    CONSTRAINT auth_password_changed_order CHECK (
        password_changed_at >= created_at
    )
);

CREATE TABLE auth_password_attempt (
    username VARCHAR(32) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    failure_count SMALLINT NOT NULL,
    blocked_until TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_password_attempt_username_valid CHECK (
        username ~ '^[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]$'
    ),
    CONSTRAINT auth_password_attempt_count_valid CHECK (
        failure_count BETWEEN 1 AND 5
    ),
    CONSTRAINT auth_password_attempt_block_order CHECK (
        blocked_until IS NULL OR blocked_until >= window_started_at
    ),
    CONSTRAINT auth_password_attempt_updated_order CHECK (
        updated_at >= window_started_at
    )
);

CREATE INDEX auth_password_attempt_cleanup_idx
    ON auth_password_attempt(updated_at);
