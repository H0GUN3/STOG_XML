# Native auth backend review corrections

Date: 2026-11-02  
Scope: H1, M1-M5 and L1 from
`.omo/evidence/native-auth-ui-001-code-review.md`

## RESULT

- Replaced the synthetic password patterns with the exact pinned SecLists MIT
  `10k-most-common.txt` corpus at commit
  `913b327317496d062bcc7cace524aaad8a693be2`.
- Repository resource contains 10,001 exact rows, 73,026 original bytes and
  verifies SHA-256
  `68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce`
  before application startup.
- Password null/NUL, spaces, NFC and length error behavior now matches the
  approved wire codes.
- All `POST /api/auth/*` bodies are capped at 8,192 bytes before JSON parsing,
  including unknown-length/chunked requests.
- Deterministic tests cover duplicate signup start, first-attempt race, fifth
  failure versus an in-flight correct password, one production-encoder match
  for known/unknown failures and the 100-row cleanup bound.
- Tests resolve the production password bean and execute real ACL behavior
  rather than copying constants or inspecting script text.
- Local operator/media directories preserve existing data, reject reparse
  points, and enforce a protected DACL granting FullControl only to the current
  user, SYSTEM and Administrators.
- V7 and retained data were not rewritten.

## EVIDENCE

- Focused review gate: 26 tests across native PostgreSQL, auth boundary,
  security/corpus/production encoder and ACL behavior; PASS.
- Final canonical generated PostgreSQL gate:
  - 22 classes
  - 145 tests
  - failures 0
  - errors 0
  - skipped 0
  - `bootJar` PASS
  - cleanup `0|0`
  - preexisting test metadata unchanged
- Final receipt:
  `review-final-gate/test-runner-result.json`.
- An earlier full review run reached 145 tests but nine TripProduct tests failed
  at context startup because PostgreSQL connection slots were exhausted. The
  new spy-backed native-auth test context was bounded to a two-connection
  Hikari pool; the final full gate passed.
- The first two corrected-runtime starts failed before opening port 8080
  because `Set-Acl` attempted to preserve an inherited audit section requiring
  `SeSecurityPrivilege`. The final implementation reads/writes only the DACL
  with owner/audit state preserved; disposable and actual operator-directory
  ACL behavior passed.

## CORRECTED RUNTIME

- Task-owned backend session: `bash_6`.
- Listener: exact `127.0.0.1:8080`.
- Existing non-session data remained:
  `3 users | 1 Kakao identity | 2 native credentials | 2 trips | 2 memberships`.
- Preserved data fingerprint before/after restart:
  `60d2ef8009b9de182d5d0b2f3b7494a8`.
- Same-user DPAPI QA credential remained readable without outputting values.
- Existing QA account login `200`, one persisted trip visible, logout `204`,
  and refresh reuse after logout `401`.
- Operator directory ACL is protected and contains only the approved three
  principals.

## REMAINING

Frontend may begin final E2E against the corrected runtime. No broader backend
matrix, migration, backup, recovery or release work remains in this review
scope.
