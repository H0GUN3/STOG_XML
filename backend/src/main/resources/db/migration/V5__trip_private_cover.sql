CREATE TABLE trip_cover (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE RESTRICT,
    created_by_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    media_type VARCHAR(32) NOT NULL CHECK (media_type = 'image/jpeg'),
    storage_key UUID UNIQUE NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 1 AND 4194304),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 2048),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 2048),
    thumbnail_byte_size BIGINT NOT NULL CHECK (thumbnail_byte_size BETWEEN 1 AND 524288),
    thumbnail_width INTEGER NOT NULL CHECK (thumbnail_width BETWEEN 1 AND 512),
    thumbnail_height INTEGER NOT NULL CHECK (thumbnail_height BETWEEN 1 AND 512),
    input_sha256 BYTEA NOT NULL CHECK (octet_length(input_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ NULL,
    CONSTRAINT trip_cover_retired_order CHECK (retired_at IS NULL OR retired_at >= created_at)
);
CREATE INDEX trip_cover_trip_idx ON trip_cover(trip_id);
CREATE INDEX trip_cover_retired_idx ON trip_cover(retired_at) WHERE retired_at IS NOT NULL;

ALTER TABLE trip ADD COLUMN cover_id UUID NULL REFERENCES trip_cover(id) ON DELETE RESTRICT;

CREATE FUNCTION enforce_trip_cover_same_trip() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.cover_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM trip_cover c WHERE c.id = NEW.cover_id AND c.trip_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'trip cover must belong to the same trip' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER trip_cover_same_trip
AFTER INSERT OR UPDATE OF cover_id ON trip
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_trip_cover_same_trip();

ALTER TABLE trip_command_execution DROP CONSTRAINT trip_command_type_valid;
ALTER TABLE trip_command_execution ADD CONSTRAINT trip_command_type_valid CHECK (command_type IN (
    'UPDATE_TRIP_V1', 'UPDATE_MEMBER_ROLE_V1', 'REMOVE_MEMBER_V1', 'LEAVE_TRIP_V1',
    'ISSUE_TRIP_INVITE_V1', 'REVOKE_TRIP_INVITE_V1', 'JOIN_TRIP_V1', 'DELETE_TRIP_V1',
    'SET_TRIP_COVER_V1', 'REMOVE_TRIP_COVER_V1'
));
