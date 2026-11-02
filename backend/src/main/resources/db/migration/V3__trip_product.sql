ALTER TABLE trip ADD COLUMN name VARCHAR(100) NULL;
ALTER TABLE trip ADD COLUMN region_label VARCHAR(100) NULL;
ALTER TABLE trip ADD CONSTRAINT trip_name_valid CHECK (
    name IS NULL OR character_length(name) BETWEEN 1 AND 100
);
ALTER TABLE trip ADD CONSTRAINT trip_region_label_valid CHECK (
    region_label IS NULL OR character_length(region_label) BETWEEN 1 AND 100
);

CREATE INDEX trip_member_user_trip_idx ON trip_member(user_id, trip_id);
CREATE INDEX trip_status_start_idx ON trip(start_date, id);
CREATE INDEX trip_status_end_idx ON trip(end_date, id);

CREATE TABLE trip_create_execution (
    principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT,
    command_type VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    input_name VARCHAR(100) NOT NULL,
    input_region_label VARCHAR(100) NOT NULL,
    input_start_date DATE NOT NULL,
    input_end_date DATE NOT NULL,
    result_trip_id UUID NOT NULL,
    result_name VARCHAR(100) NOT NULL,
    result_region_label VARCHAR(100) NOT NULL,
    result_start_date DATE NOT NULL,
    result_end_date DATE NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    result_as_of_date DATE NOT NULL,
    result_role VARCHAR(16) NOT NULL,
    result_member_count BIGINT NOT NULL,
    result_plan_version BIGINT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_user_id, command_type, request_id),
    CONSTRAINT trip_create_command_valid CHECK (command_type = 'CREATE_TRIP_V1'),
    CONSTRAINT trip_create_input_name_valid CHECK (character_length(input_name) BETWEEN 1 AND 100),
    CONSTRAINT trip_create_input_region_valid CHECK (character_length(input_region_label) BETWEEN 1 AND 100),
    CONSTRAINT trip_create_input_dates_valid CHECK (
        input_start_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND input_end_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND input_start_date <= input_end_date
    ),
    CONSTRAINT trip_create_result_name_valid CHECK (character_length(result_name) BETWEEN 1 AND 100),
    CONSTRAINT trip_create_result_region_valid CHECK (character_length(result_region_label) BETWEEN 1 AND 100),
    CONSTRAINT trip_create_result_dates_valid CHECK (
        result_start_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND result_end_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND result_as_of_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND result_start_date <= result_end_date
    ),
    CONSTRAINT trip_create_result_status_valid CHECK (
        (result_status = 'ONGOING' AND result_start_date <= result_as_of_date AND result_as_of_date <= result_end_date)
        OR (result_status = 'UPCOMING' AND result_start_date > result_as_of_date)
        OR (result_status = 'PAST' AND result_end_date < result_as_of_date)
    ),
    CONSTRAINT trip_create_result_role_valid CHECK (result_role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT trip_create_result_member_count_valid CHECK (result_member_count >= 1),
    CONSTRAINT trip_create_result_plan_version_valid CHECK (result_plan_version >= 0),
    CONSTRAINT trip_create_expiry_valid CHECK (expires_at = completed_at + INTERVAL '24 hours')
);
