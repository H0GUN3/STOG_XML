CREATE TABLE stog_user (
    id UUID PRIMARY KEY
);

CREATE TABLE trip (
    id UUID PRIMARY KEY,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    plan_version BIGINT NOT NULL,
    CONSTRAINT trip_dates_valid CHECK (start_date <= end_date),
    CONSTRAINT trip_plan_version_nonnegative CHECK (plan_version >= 0)
);

CREATE TABLE trip_member (
    trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (trip_id, user_id),
    CONSTRAINT trip_member_role_valid CHECK (role IN ('ADMIN', 'MEMBER'))
);

CREATE TABLE place (
    id UUID PRIMARY KEY
);

CREATE TABLE trip_item (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES place(id) ON DELETE RESTRICT,
    created_by_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    schedule_date DATE,
    sort_order BIGINT,
    CONSTRAINT trip_item_schedule_complete CHECK (
        (schedule_date IS NULL AND sort_order IS NULL)
        OR (schedule_date IS NOT NULL AND sort_order IS NOT NULL AND sort_order > 0)
    )
);
CREATE INDEX trip_item_trip_idx ON trip_item(trip_id);
CREATE INDEX trip_item_place_idx ON trip_item(place_id);
CREATE UNIQUE INDEX trip_item_one_basket_place_idx
    ON trip_item(trip_id, place_id)
    WHERE schedule_date IS NULL AND sort_order IS NULL;

CREATE TABLE request_execution (
    principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    command_type VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    input_trip_id UUID NOT NULL,
    input_place_id UUID NOT NULL,
    input_expected_plan_version BIGINT NOT NULL,
    result_kind VARCHAR(24) NOT NULL,
    result_trip_item_id UUID NOT NULL,
    result_place_id UUID NOT NULL,
    result_plan_version BIGINT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_user_id, command_type, request_id),
    CONSTRAINT request_command_valid CHECK (command_type = 'CREATE_TRIP_ITEM_BASKET_V1'),
    CONSTRAINT request_input_version_nonnegative CHECK (input_expected_plan_version >= 0),
    CONSTRAINT request_result_kind_valid CHECK (result_kind IN ('CREATED', 'ALREADY_EXISTS')),
    CONSTRAINT request_result_version_nonnegative CHECK (result_plan_version >= 0),
    CONSTRAINT request_expiry_valid CHECK (expires_at = completed_at + INTERVAL '24 hours')
);
