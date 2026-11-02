CREATE TABLE auth_identity (
    provider VARCHAR(16) NOT NULL,
    subject VARCHAR(32) NOT NULL,
    user_id UUID NOT NULL UNIQUE REFERENCES stog_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (provider, subject),
    CONSTRAINT auth_identity_provider_valid CHECK (provider = 'KAKAO'),
    CONSTRAINT auth_identity_subject_canonical CHECK (subject ~ '^[1-9][0-9]{0,31}$')
);

CREATE TABLE auth_login_exchange (
    provider VARCHAR(16) NOT NULL,
    credential_hash BYTEA NOT NULL,
    subject VARCHAR(32) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider, credential_hash),
    CONSTRAINT auth_exchange_provider_valid CHECK (provider = 'KAKAO'),
    CONSTRAINT auth_exchange_subject_canonical CHECK (subject ~ '^[1-9][0-9]{0,31}$'),
    CONSTRAINT auth_exchange_expiry_valid CHECK (expires_at > consumed_at)
);

CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    access_token_hash BYTEA NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT auth_session_access_expiry_valid CHECK (access_expires_at > created_at AND access_expires_at <= absolute_expires_at),
    CONSTRAINT auth_session_absolute_expiry_valid CHECK (absolute_expires_at > created_at),
    CONSTRAINT auth_session_revoke_valid CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX auth_session_user_idx ON auth_session(user_id);

CREATE TABLE auth_refresh_token (
    token_hash BYTEA PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_session(id) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT auth_refresh_expiry_valid CHECK (expires_at > issued_at),
    CONSTRAINT auth_refresh_used_valid CHECK (used_at IS NULL OR used_at >= issued_at)
);
CREATE UNIQUE INDEX auth_refresh_one_current_per_session_idx
    ON auth_refresh_token(session_id) WHERE used_at IS NULL;
