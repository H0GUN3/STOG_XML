ALTER TABLE trip ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE trip ADD COLUMN deleted_by_user_id UUID NULL REFERENCES stog_user(id) ON DELETE RESTRICT;
ALTER TABLE trip ADD CONSTRAINT trip_deleted_fields_together CHECK (
    (deleted_at IS NULL AND deleted_by_user_id IS NULL)
    OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL)
);

ALTER TABLE trip_create_execution ADD COLUMN invalidated_at TIMESTAMPTZ NULL;
CREATE INDEX trip_create_result_trip_idx ON trip_create_execution(result_trip_id) WHERE invalidated_at IS NULL;
CREATE INDEX trip_active_status_start_idx ON trip(start_date, id) WHERE deleted_at IS NULL;
CREATE INDEX trip_active_status_end_idx ON trip(end_date, id) WHERE deleted_at IS NULL;

ALTER TABLE trip_member ADD COLUMN member_label VARCHAR(40) NULL;
ALTER TABLE trip_member ADD CONSTRAINT trip_member_label_valid CHECK (
    member_label IS NULL OR character_length(member_label) BETWEEN 1 AND 40
);

CREATE TABLE trip_invitation (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE RESTRICT,
    token_hash BYTEA UNIQUE NOT NULL CHECK (octet_length(token_hash) = 32),
    created_by_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    CONSTRAINT trip_invitation_expiry_exact CHECK (expires_at = issued_at + INTERVAL '10 minutes'),
    CONSTRAINT trip_invitation_revoke_order CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);
CREATE UNIQUE INDEX trip_invitation_one_unrevoked_idx ON trip_invitation(trip_id) WHERE revoked_at IS NULL;
CREATE INDEX trip_invitation_trip_idx ON trip_invitation(trip_id);

CREATE TABLE trip_command_execution (
    principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    trip_id UUID NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    input_sha256 BYTEA NOT NULL CHECK (octet_length(input_sha256) = 32),
    response_status SMALLINT NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    result_json JSONB NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    completed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_user_id, trip_id, command_type, request_id),
    CONSTRAINT trip_command_type_valid CHECK (command_type IN (
        'UPDATE_TRIP_V1', 'UPDATE_MEMBER_ROLE_V1', 'REMOVE_MEMBER_V1', 'LEAVE_TRIP_V1',
        'ISSUE_TRIP_INVITE_V1', 'REVOKE_TRIP_INVITE_V1', 'JOIN_TRIP_V1', 'DELETE_TRIP_V1'
    )),
    CONSTRAINT trip_command_expiry_exact CHECK (expires_at = completed_at + INTERVAL '24 hours')
);
CREATE INDEX trip_command_expiry_idx ON trip_command_execution(expires_at);

CREATE TABLE trip_invitation_attempt (
    id BIGSERIAL PRIMARY KEY,
    principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32),
    attempted_at TIMESTAMPTZ NOT NULL,
    succeeded BOOLEAN NOT NULL
);
CREATE INDEX trip_invitation_attempt_principal_idx ON trip_invitation_attempt(principal_user_id, attempted_at);
CREATE INDEX trip_invitation_attempt_token_idx ON trip_invitation_attempt(token_hash, attempted_at);
