# R3c removed candidate patch (review only)

This artifact preserves the removed candidate without changing the verified R3b source tree. It is not applied or GREEN.

## PlaceCatalogService search replacement

```java
boolean fuzzy=query.codePointCount(0,query.length())>=3;
String escaped=escapeLike(query);
StringBuilder sql=new StringBuilder(
    "WITH configured AS MATERIALIZED ("+
    "SELECT set_config('pg_trgm.similarity_threshold','0.30',true)), "+
    "ranked AS MATERIALIZED (SELECT id,name,category,region_label,address,latitude,longitude,normalized_name,"+
    "CASE WHEN normalized_name=? THEN 0 "+
    "WHEN normalized_name LIKE ? ESCAPE '\\' THEN 1 "+
    "WHEN normalized_name LIKE ? ESCAPE '\\' THEN 2 ELSE 3 END match_class,"+
    "public.similarity(normalized_name,?)::real match_score "+
    "FROM place CROSS JOIN configured WHERE name IS NOT NULL AND normalized_name IS NOT NULL");
// optional normalized_region_label / normalized_category equality predicates
sql.append(" AND (normalized_name LIKE ? ESCAPE '\\' "+
    "OR (? AND normalized_name OPERATOR(public.%) ?)))");
// V2 cursor continuation:
// match_class > lastClass OR same class and score < lastScore OR same score and
// (normalized_name > lastName OR same name and id > lastId)
sql.append(" ORDER BY match_class ASC,match_score DESC,normalized_name ASC,id ASC LIMIT ?");
```

Parameter order was query, escaped prefix, escaped infix, query score, filters, escaped infix, fuzzy-enabled boolean, query, complete cursor tuple, limit+1. All values were typed binds.

## Place cursor V2 candidate

- Payload/AAD version `2`, ranking version `1`, AAD `STOG_PLACE_CURSOR_V2_RANKED`.
- Added `lastMatchClass` int32 and raw float32 `lastScore` before last normalized name and UUID.
- Decode rejected class outside `0..3`, nonfinite/out-of-range score, V1 AAD/version, principal/filter/limit mismatch, tamper, expiry, oversize and trailing data.
- Existing 4096 ASCII/15-minute bounds remained.

## Preserved executable RED/regression intent

```java
@Test void infixAndTypoQueriesFindTheExistingCommonPlace() throws Exception {
    UUID target=place("SEMAS","rank-red","성산일출봉","성산일출봉",
        "관광지","관광지","제주","제주",null,null,null,33.4,126.9);
    JsonNode infix=body(get("place-access","/api/places?query="+enc("일출"),"application/json"));
    assertEquals(target.toString(),infix.at("/items/0/place_id").asText());
    JsonNode typo=body(get("place-access","/api/places?query="+enc("성산일출봄"),"application/json"));
    assertEquals(target.toString(),typo.at("/items/0/place_id").asText());
}
```

Additional removed GREEN tests covered exact/prefix/infix/similarity ranking, UUID ties, two-item keyset pages, region/category similarity filters, two-character substring-only behavior, literal `%`/`_`/backslash/SQL-looking text, V1 cursor rejection and invalid rank.

## Why removed

Flyway executing the candidate V7 as the canonical generated non-superuser failed before tests with `ERROR: permission denied for schema public`. Keeping V7 or GREEN assertions in source would leave the accepted backend baseline unbuildable. The RED log and this candidate artifact preserve review history without weakening tests or claiming GREEN.
