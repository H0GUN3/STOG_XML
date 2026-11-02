# Preserved failed verifier run

- Command: `Run-Android-Gate.ps1`
- Result: failed before device tests at `:app:compileDebugAndroidTestJavaWithJavac`.
- Root cause: three lambdas in `RealBackendTripCollaborationUiTest` called the checked-exception helper `detail(...)`; Java rejected the unhandled `Exception` at the ADMIN-leave, stale-role, and MEMBER-leave assertions.
- Scope: verifier test compile fault only; no production code executed or changed by this failure.
- Correction: read each detail/version before constructing the callback lambda.
- Subsequent proof: `logs/android-full-gate.log` is the final PASS with JVM tests, 31/31 instrumentation, Lint, and assemble.
