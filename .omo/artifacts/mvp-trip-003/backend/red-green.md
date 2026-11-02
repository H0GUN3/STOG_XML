# MVP-TRIP-003 backend RED/GREEN

## Behavioral RED

Command: isolated runner with `PlaceCatalogHttpIntegrationTest`, bootJar skipped. Exit `1`: authenticated real-servlet UTF-8 search expected `200` and observed absent-route non-success before any R3 production code. Evidence: `logs/behavioral-red.log`, `behavioral-red-exit-code.txt`.

## Cursor bound correction

The compact binary AES-GCM Place cursor binds principal, limit, normalized query/region/category, last normalized name and Place UUID. A legitimate maximum tuple using four-byte Unicode produces a token greater than 1024 characters and no more than 4096. TASK/API were narrowly corrected to 4096. `PlaceCursorCodecTest.legitimateMaximumUnicodeTupleFitsCorrectedBoundAndRoundTrips` executes that proof.

## GREEN

Focused command selected `PlaceCatalogHttpIntegrationTest`, `PlaceCursorCodecTest`, and existing `BasketSavePostgresIntegrationTest`. Final exit `0`: 29/29, failures/errors/skips `0/0/0` (3 real HTTP catalog, 2 cursor, 24 basket regression). Evidence: `logs/focused-green-final-2.log`, `focused-green-final-2-exit-code.txt`, `focused-green-final/test-runner-result.json`.

Coverage includes real servlet UTF-8 URL decoding, exact DTO/nulls, prefix+region/category pages, literal `%`/`_`/backslash escaping, duplicate/unknown query rejection, JSON error negotiation, legacy/missing detail, read-only DB state, cursor principal/filter/limit/tamper/maxima, and existing basket response replay with the same common Place.

Preserved failed iterations: the first GREEN exposed an incorrect SQL ESCAPE literal; a later run exposed only a test tampering an unused final base64 bit. Both are preserved and neither is claimed as product success.
