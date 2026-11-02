ALTER TABLE place
    ADD COLUMN source_type VARCHAR(32) NULL,
    ADD COLUMN source_key VARCHAR(200) NULL,
    ADD COLUMN name VARCHAR(200) NULL,
    ADD COLUMN normalized_name VARCHAR(200) NULL,
    ADD COLUMN category VARCHAR(100) NULL,
    ADD COLUMN normalized_category VARCHAR(100) NULL,
    ADD COLUMN region_label VARCHAR(100) NULL,
    ADD COLUMN normalized_region_label VARCHAR(100) NULL,
    ADD COLUMN address VARCHAR(300) NULL,
    ADD COLUMN road_address VARCHAR(300) NULL,
    ADD COLUMN description VARCHAR(4000) NULL,
    ADD COLUMN latitude NUMERIC(9,6) NULL,
    ADD COLUMN longitude NUMERIC(10,6) NULL,
    ADD CONSTRAINT place_source_pair CHECK ((source_type IS NULL) = (source_key IS NULL)),
    ADD CONSTRAINT place_name_pair CHECK ((name IS NULL) = (normalized_name IS NULL)),
    ADD CONSTRAINT place_category_pair CHECK ((category IS NULL) = (normalized_category IS NULL)),
    ADD CONSTRAINT place_region_pair CHECK ((region_label IS NULL) = (normalized_region_label IS NULL)),
    ADD CONSTRAINT place_coordinates_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT place_latitude_bounds CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT place_longitude_bounds CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    ADD CONSTRAINT place_catalog_text_nonblank CHECK (
        (source_type IS NULL OR character_length(source_type) >= 1) AND
        (source_key IS NULL OR character_length(source_key) >= 1) AND
        (name IS NULL OR character_length(name) >= 1) AND
        (normalized_name IS NULL OR character_length(normalized_name) >= 1) AND
        (category IS NULL OR character_length(category) >= 1) AND
        (normalized_category IS NULL OR character_length(normalized_category) >= 1) AND
        (region_label IS NULL OR character_length(region_label) >= 1) AND
        (normalized_region_label IS NULL OR character_length(normalized_region_label) >= 1) AND
        (address IS NULL OR character_length(address) >= 1) AND
        (road_address IS NULL OR character_length(road_address) >= 1) AND
        (description IS NULL OR character_length(description) >= 1)
    );

CREATE UNIQUE INDEX place_source_identity_idx ON place(source_type, source_key)
    WHERE source_type IS NOT NULL AND source_key IS NOT NULL;
CREATE INDEX place_normalized_name_prefix_idx ON place(normalized_name varchar_pattern_ops, id)
    WHERE normalized_name IS NOT NULL;
