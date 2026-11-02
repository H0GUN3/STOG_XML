# Native auth backend code review

Date: 2026-11-02
Reviewer role: `omo-native-code-reviewer`
Scope: backend native username/password auth only; `app/**` and unfinished frontend work were not reviewed.

- codeQualityStatus: **BLOCK**
- recommendation: **REQUEST_CHANGES**
- reportPath: `.omo/evidence/native-auth-ui-001-code-review.md`

## Review basis

Reviewed the task contract and inventory, backend implementation evidence, actual auth/controller/security/blocklist/V7/launcher sources, focused native-auth tests, shared auth/replay tests, restricted-owner tests, PostgreSQL runner receipt, and diagnostics. No test was rerun, no database was accessed, and no process or repository state was changed other than writing this report.

The requested `remove-ai-slops` and `programming` skills were not registered in this session, so they could not be loaded as skill files. I applied their criteria as included in the reviewer instructions. The diff violates both perspectives in the test suite: the PBKDF2 test recreates production constants instead of testing the production bean, and the launcher test only searches source text rather than proving launcher behavior or privacy. The generated blocklist cardinality is also an overfit substitute for the approved common/compromised-password dataset requirement.

## CRITICAL

None.

## HIGH

### H1. The offline blocklist is not the approved pinned common/compromised-password dataset

- `backend/src/main/resources/security/common-passwords.provenance.md:5-10`
- `backend/src/main/resources/security/common-passwords.txt:8-55`
- `backend/src/main/java/com/stog/backend/PasswordBlocklist.java:18-21`
- `backend/src/main/java/com/stog/backend/PasswordBlocklist.java:44-45`
- `backend/src/main/java/com/stog/backend/PasswordBlocklist.java:50-62`
- Contract: `.omo/artifacts/native-auth-ui-20261102/backend-contract.md:54-59`

The approved design requires at least 10,000 **common/compromised** values with source, pinned revision, and license recorded. The artifact instead declares a project-original generated-pattern policy with no external source or pinned revision. Runtime cardinality is inflated by multiplying each `numeric4:` base by 10,000; 12 of the 14 generated bases produce only passwords shorter than the separately enforced 15-code-point minimum, so 120,000 of the claimed 140,000 generated candidates can never reach blocklist evaluation as acceptable-length passwords. The remaining values are synthetic suffix combinations, not evidence of a pinned common/compromised corpus.

This is a material substitution of an approved security control while the task and implementation evidence claim the pinned blocklist is complete. Replace it with the approved repository-owned pinned dataset and provenance, or obtain an explicit contract change; add tests against the actual production resource and representative accepted-length compromised values.

## MEDIUM

### M1. Password validation violates the exact API contract and rejects an explicitly allowed password class

- `backend/src/main/java/com/stog/backend/AuthController.java:59-66`
- `backend/src/main/java/com/stog/backend/AuthService.java:324-331`
- `backend/src/main/java/com/stog/backend/AuthException.java:15-29`
- Contract: `.omo/artifacts/native-auth-ui-20261102/backend-contract.md:54-60`
- Contract: `.omo/artifacts/native-auth-ui-20261102/backend-contract.md:158-161`

`AuthService.password` maps too-short, too-long, over-byte-limit, and NUL-containing passwords to `INVALID_REQUEST`, although the approved error contract assigns password length failures to `PASSWORD_NOT_ACCEPTABLE`. In addition, controller-level `@NotBlank` rejects a 15+ character all-space password before normalization even though the approved policy explicitly permits spaces and imposes no composition rule. This makes signup/login wire behavior differ from the client contract and causes validation to be split inconsistently between DTO annotations and the service.

Use null/type validation at the JSON boundary, centralize password policy in the service, and return the contracted error code for password-policy failures. Add exact boundary tests for 15/128 code points, 1024 UTF-8 bytes, NFC, spaces, and the response code.

### M2. Auth JSON bodies have no pre-deserialization size bound

- `backend/src/main/java/com/stog/backend/AuthController.java:59-72`
- `backend/src/main/resources/application.properties:6-14`
- Contract: `.omo/artifacts/native-auth-ui-20261102/backend-contract.md:74-76`

Field `@Size` checks run only after Jackson has parsed the body. The only configured request-size limits are multipart limits for cover uploads; there is no auth/JSON body cap. A very large string or unknown-field value can therefore consume parser memory/CPU before validation, contrary to the explicit over-limit-body requirement. Loopback-only exposure reduces current reachability but does not satisfy the in-scope boundary contract.

Add an actual bounded JSON request-body mechanism and a web-boundary test that proves oversized auth input is rejected before service/database work.

### M3. Throttle/enumeration acceptance is asserted more broadly than the tests prove

- `backend/src/main/java/com/stog/backend/AuthService.java:82-95`
- `backend/src/main/java/com/stog/backend/AuthService.java:140-179`
- `backend/src/test/java/com/stog/backend/NativeAuthPostgresIntegrationTest.java:120-144`
- `docs/tasks/NATIVE-AUTH-UI-001.md:92-100`
- `docs/tasks/NATIVE-AUTH-UI-001.md:110-113`

The production SQL appears to serialize existing throttle rows and uses an atomic upsert for failures, but the native-auth suite only exercises sequential failures. It does not prove concurrent first-row creation/increment, a correct-password race at the fifth failure, unknown-username row behavior, the 100-row cleanup bound, or that known and unknown syntactically valid logins each invoke exactly one same-parameter PBKDF2 check. These are explicit acceptance/security properties, not optional implementation details.

Add deterministic concurrency tests using the repository's JDBC barrier style and a counting production-injected encoder test. Avoid sleeps or timing-threshold assertions.

### M4. Two new tests are implementation-mirroring/tautological and can stay green when production regresses

- `backend/src/test/java/com/stog/backend/NativeAuthSecurityTest.java:41-52`
- `backend/src/main/java/com/stog/backend/SecurityConfig.java:48-52`
- `backend/src/test/java/com/stog/backend/LocalBackendLauncherTest.java:14-21`
- `backend/scripts/Start-LocalBackend.ps1:46-51`

`encoderUsesVersionedPbkdf2Sha256Contract` constructs a second encoder from copied constants instead of resolving the `SecurityConfig` bean. It would pass if production changed algorithms or work factors. `normalLauncherProvidesPersistentPrivateMediaRoot` only checks that source text contains several strings; it does not execute the relevant safe path, verify the resulting path, or establish that the directory ACL is private. These violate the remove-ai-slops and programming perspectives and leave claimed security behavior unprotected.

Test the production bean directly. Replace the launcher source-text assertions with a behavior-level, non-destructive seam that verifies normal-directory/reparse rejection and the intended ACL/privacy invariant.

### M5. The launcher does not establish or verify the documented private media-root ACL

- `backend/scripts/Initialize-LocalBackend.ps1:53-56`
- `backend/scripts/Start-LocalBackend.ps1:46-51`
- `docs/tasks/NATIVE-AUTH-UI-001.md:41-45`

The launcher creates the directory and rejects a reparse-point root, but neither initialization nor startup restricts or validates its ACL. It relies entirely on inherited `%LOCALAPPDATA%` permissions. That is usually safe on a normal profile, but the implementation cannot guarantee the documented “private media root” when the parent has broader inherited access. This is an in-scope local retained-media issue, separate from excluded public TLS/network exposure.

Validate or establish a same-user/private ACL before startup, while retaining SYSTEM/administrator access as appropriate, and test the observable ACL result rather than script text.

## LOW

### L1. Boundary tests conflate unknown-field rejection with password validation

- `backend/src/test/java/com/stog/backend/AuthenticationBoundaryTest.java:80-85`

The login test sends both an unknown field and an invalid one-character password, so it cannot identify which rule produced the 400. Split these cases so duplicate/unknown/type/null/body-limit handling and password-policy mapping are independently protected.

## Verified strengths / no finding

- PBKDF2 production configuration matches the approved 600,000-iteration HMAC-SHA-256, 16-byte salt, 256-bit output, versioned-id design: `backend/src/main/java/com/stog/backend/SecurityConfig.java:48-52`.
- Signup inserts user, credential, session, and refresh token inside one transaction; the late-session-failure and duplicate-signup tests cover rollback/atomicity.
- Login performs the real/dummy PBKDF2 match before blocked-result handling, and external 401 bodies/headers are compared for unknown, wrong, and blocked sequential cases.
- Session issuance is shared with the existing Kakao flow, while existing refresh rotation/replay revoke/logout tests remain present.
- V7 is additive and uses nullable display name plus new credential/throttle tables; it does not rewrite V1-V6 identity/domain rows: `backend/src/main/resources/db/migration/V7__native_password_auth.sql:1-46`.
- The inspected runner receipt records Gradle exit 0, non-superuser/generated-schema execution, cleanup `0|0`, unchanged preexisting metadata, and generated media cleanup. The source suite contains 135 `@Test` methods, consistent with the task summary.
- Language-server diagnostics were clean for `AuthService.java`, `AuthController.java`, and `NativeAuthPostgresIntegrationTest.java`.

## Excluded release concerns

Password recovery/account deletion and public listener/TLS/proxy/firewall remain explicitly release-excluded. They are not findings in this review. The blockers above concern the approved local/native-auth implementation itself.

## Blockers before approval

1. Implement the approved pinned common/compromised-password blocklist with truthful source/revision/license provenance and meaningful accepted-length coverage.
2. Correct password boundary/error behavior, including permitted spaces and `PASSWORD_NOT_ACCEPTABLE` mapping.
3. Enforce an actual auth JSON body-size bound.
4. Add deterministic tests for throttle races/bounds and one-PBKDF2 known/unknown behavior.
5. Replace implementation-mirroring encoder/launcher tests and substantiate the private media-root ACL guarantee.
