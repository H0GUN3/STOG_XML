# Invitation parser behavioral RED

Command:

```text
./gradlew.bat :app:testDebugUnitTest --tests com.stog.app.trip.InvitationInputTest
```

Observed before the parser accepted the custom URI:

```text
1 test completed, 1 failed
InvitationInputTest > acceptsCanonicalRawTokenAndOnlyTheExactCustomUri FAILED
java.lang.AssertionError at InvitationInputTest.java:13
BUILD FAILED
```

The implementation was then changed to require the exact `stog` scheme, `trip-invitations` authority, one canonical 43-character Base64URL path segment, and no query, fragment, userinfo, or port. The final unit-test report records 1 test, 0 failures, and 0 errors. No capability value is recorded in this artifact.
