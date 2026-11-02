# Producer attempt record

- Before the emulator completed boot, `adb wait-for-device && adb shell settings get system font_scale && adb shell settings put system font_scale 1.3` failed with `cmd: Can't find service: settings` (exit 20). After `sys.boot_completed=1`, the same settings service was available; the test ran with font scale 1.3 and cleanup restored 1.0.
- Initial picker UI assertion did not scroll the nested detail surface and failed because the select button had an empty global visible rectangle. The test was corrected to use Espresso `scrollTo()` before the action.
- Espresso Back cannot operate while DocumentsUI is resumed (`NoActivityResumedException`). The native picker cancellation test was corrected to send `KEYCODE_BACK` through instrumentation, then observe the resumed detail state.
- Activity-decor drawing omitted the dialog window. Captures use `UiAutomation.takeScreenshot()` so the landscape remove-confirmation dialog is present in the inspected artifact.
