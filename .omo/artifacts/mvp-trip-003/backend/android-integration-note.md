# R3a Android integration facts for st_01a10fe2

- Canonical wire remains `GET /api/places` and `GET /api/places/{place_id}` in TASK §5; exact DTO/nullability and errors are unchanged.
- Cursor cap correction is final: maximum **4096 ASCII** (not 1024). Actual compact codec test proves the valid maximum Unicode tuple exceeds 1024 and fits 4096.
- Search is the bounded R3a engineering seam: normalized-name prefix plus optional exact normalized `region`/`category`, default limit 20/max 50; it does not claim full pg_trgm behavior.
- Summary keys: `place_id,name,category,region_label,address,latitude,longitude`; detail adds `road_address,description`. Nullable fields and coordinate pair follow TASK.
- Existing basket POST is unchanged. Search/detail are authenticated and use private/no-store plus nosniff.
- V6 retains legacy ID-only Places; catalog search excludes them and detail returns `PLACE_NOT_FOUND` for them.
- WGS84 fixture coordinates are bounded decimal pairs to six places; no guessed/partial coordinates.
- Existing `TripViewModel` ownership is parent-fragment scoped in current app source despite the TASK's historical “Activity-scoped” wording. Preserve actual owner; do not move it.
