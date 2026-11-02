-- REVIEW ONLY: blocked because generated/runtime roles cannot resolve public.gin_trgm_ops.
CREATE INDEX place_normalized_name_trgm_idx
    ON place USING gin (normalized_name public.gin_trgm_ops)
    WHERE normalized_name IS NOT NULL;
