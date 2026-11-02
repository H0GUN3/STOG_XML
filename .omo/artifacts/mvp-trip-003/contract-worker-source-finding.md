# Source facts for R3 contract worker st_01a10f02

- Selected source is now actually inspected: SEMAS/data.go.kr dataset `15083033`, snapshot `20260630`, free, unrestricted, one 352,699,739-byte anonymous ZIP. Normal portal check returned `needCaptcha:false`; no user/account gate remains.
- SHA-256: `57aa361544108ff4fc73334f87d2ced61e41fe8638dd70bcd842930f865d2386`. Archive is retained only under ignored `.omo/local/r3-source-qa/`.
- Archive contains 16 regional UTF-8 CSVs + one UTF-8 README. All 16 headers are identical, 39 columns.
- Exact contract-impact fields: `상가업소번호`, `상호명`, `지점명`, three category code/name levels, KSIC code/name, administrative codes/names, `지번주소`, `도로명주소`, building/postal/detail fields, `경도`, `위도`.
- `source_key <- 상가업소번호`; same-operator API calls it `bizesId`, a serial number assigned to the establishment. `source_type` can be fixed SEMAS catalog type.
- Official same-operator API metadata documents longitude/latitude as WGS84. CSV axes are explicit. Source has more than six decimals, so current V6 NUMERIC scales need deterministic six-decimal rounding.
- Recommended minimal mapping: name=`상호명`; category=`상권업종소분류명`; region=`시도명`; address=`지번주소`; road address=`도로명주소`; WGS84 lon/lat from exact columns; description/image null.
- Contract must explicitly decide treatment of separate `지점명`, category code and richer administrative codes; do not silently concatenate/discard them or add unapproved API fields.
- Full-R3 dedup remains v1.4 normalized name + coordinate distance + address, not name-only. R3a no-PostGIS/no-pg_trgm boundary remains unchanged.
- Full evidence: `.omo/artifacts/mvp-trip-003/public-data-source.md`; machine receipts under `.omo/artifacts/mvp-trip-003/download-check/`.
