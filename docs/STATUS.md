# Project status

## Product

Air Touch 64 is an Android patcher for Photoshop Touch. The user selects the original APK; the app transforms it into the PS Touch Revived ARM64 APK using the bundled patch set. The original Adobe APK is not included.

The app supports Android 8.0 (API 26) and newer, and has Portuguese and English strings. It accepts only the source APK whose SHA-256 is listed in the bundle manifest:

```text
c57e409493f46f4b9404678a777e23cee7fedcf921427435f4767efda5823a85
```

This is the Photoshop Touch 1.3.7 APK variant used to create the bundle. Other repackaged or region-specific APKs are rejected even if they have the same app version.

## App flow

1. The user picks an APK through Android's document picker. The app copies it to its cache and checks its SHA-256 and required entries.
2. The engine applies the SWF patch, DEX patches and resource overrides, then assembles the AIR ARM64 shell and native libraries from the bundle.
3. The APK is aligned, signed with a persistent per-install key, and verified on device.
4. The user can open the Android package installer or save the APK through the system document picker.

Patching runs in the ViewModel, not a foreground service. Keep Air Touch 64 open while patching. The signing key is stored in the app's private files; uninstalling the app removes it. APKs produced by different app installations therefore have different signing certificates and cannot update one another in place.

The source Android package installer may require the user to allow installs from Air Touch 64. The generated APK uses a different signing key from Adobe's original APK, so it installs as a separate app rather than updating the original.

## Project pieces

- `app/`: Compose UI, APK import/export, install handoff and on-device orchestration.
- `engine/`: Kotlin patch engine shared by Android and the host runner. It validates inputs and patch outputs, writes aligned ZIP entries, signs and verifies the output.
- `app/src/main/assets/bundle.zip`: format 1 bundle, about 13.5 MiB, named `PS Touch Revived ARM64` version `1.0.0`.
- `tools/make_bundle.py`: host-side bundle generator. It checks that the shell, source resources, overrides and binary deltas reproduce the final APK entry for entry.

## Build and run

The Gradle wrapper uses Gradle 8.13. The project targets Java 17 bytecode; Java 21 is the configured local runtime used for the commands below.

Build the debug app:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Run the host patcher with the supported source APK:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="SOURCE.apk app/src/main/assets/bundle.zip build/out.apk build/test-keys"
```

Run the Android instrumentation test. It expects the same supported source APK at `/data/local/tmp/pstouch-source.apk` on the connected device:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:connectedDebugAndroidTest
```

The release build reads signing values from an untracked `keystore.properties` file or the `AIRTOUCH_RELEASE_*` environment variables. See `keystore.properties.example`. Without release signing configured, the release APK is unsigned.

To regenerate the Android bundle, build the AIR shell and final APK on a host with AIR SDK, FFDec, NDK and `bsdiff4` available, then run:

```sh
python3 tools/make_bundle.py --base BASE.apk --final FINAL.apk --source SOURCE.apk --output app/src/main/assets/bundle.zip
```

The `--base`, `--final` and `--source` APKs are host build inputs and are not stored in this repository.

## Verified behavior on record

The previous project status recorded these successful checks:

- Bundle self-check reproduced every non-signature entry of the host-built final APK from the source APK and bundle.
- Host engine output matched all 345 non-signature APK entries, passed APK signature verification, and had 16 KiB native library alignment plus 4-byte stored-entry alignment.
- The app compiled and packaged; the full patch pipeline, signing and verification passed on a Galaxy S24 Ultra running Android 16.
- The patched app launched on that device and passed AIR's runtime integrity check without the modified-package warning.

These are recorded results from the previous validation run, not checks rerun while updating this document.

## Canvas touch offset with system navigation

Expected: the brush follows the touch and the canvas toolbar remains visible with Android navigation shown, in both button and gesture modes.

Cause: AIR's `AIRWindowSurfaceView.surfaceChanged()` treats every same-orientation height shrink as a soft keyboard resize. It queues `nativePerformWindowPanning(delta)`, sets `mSurfaceChangedForSoftKeyboard` and returns before updating `mHt` or sending the `ON_SIZE_CHANGED` event. When the navigation bar appears the surface shrinks by exactly that bar (2260 to 2134 px on the A54 below), so the Stage keeps the taller size while the surface is shorter and drawing ends up offset from the touch point. This is why hiding the navigation bar made the offset disappear: the shrink, and with it the false keyboard branch, never happened.

Fix: the bundle's `classes.dex` patch now gates that branch on the IME inset. `surfaceChanged()` asks `WindowInsets.getInsets(WindowInsets.Type.ime()).bottom` (API 30+) and only takes the keyboard panning path when an IME is actually visible; otherwise it falls through to the normal resize path, which updates `mHt`, dispatches the size-changed event and resets `mSkipHeightFromTop`. Below API 30 and when insets are unavailable the previous behaviour is kept. `tools/patch_bundle_dex.py` regenerates the bundle delta from the patched smali; the canonical patch lives in the PS Touch Revived `experiments/ttpixel-arm64-test/patch-android-insets.py`.

Status: confirmed on a Galaxy A54 (`SM-A546E`), Android 15, 1080x2340, 421 dpi override, three-button navigation. A cold launch with the navigation bar visible keeps the `Ps Touch` top bar and newly drawn strokes land under the finger; before the fix the same launch drew the canvas about 122 px high and hid that toolbar, and leaving the app and returning from recents repaired it. That recents round trip recreates the surface and takes the normal resize path, which is consistent with the stale-height explanation above.

The bundle carries `classes.dex` SHA-256 `3181ea573841d2c0ae0d75fcd2576391a5f37a6382ffa08440652e537da42798`; the APK generated from it in this run is `1595140abdd6898292c53877d6115fc0738080bd4a7cf53c2ce9716bafa9e484`. The first build of this guard compared `SDK_INT` with `if-lt`, which skipped the inset check on API 30+ and left the bug in place; the comparison is `if-ge` now. Gesture navigation, landscape, the IME/keyboard path and the S24 Ultra remain untested.

Actual: on the Galaxy A54 test setup below, showing the three-button navigation bar shifts the brush upward by about 121–122 px and hides the canvas top toolbar. Hiding the bar restores alignment and the toolbar; showing it again reproduces the failure. The user confirmed that newly drawn strokes align while navigation is hidden. This is a workaround observed during testing, not a fix.

Reproduction: open a blank document in the test package, keep three-button navigation visible, and draw at multiple canvas heights. Then hide navigation with `adb shell settings put global policy_control immersive.navigation=air.org.psccrevived.pstouchtest`, draw again, and restore the prior setting. The original device setting was absent; restore it with `adb shell settings delete global policy_control`. The original PS Touch package was not replaced.

On 2026-11-01, this was measured on a Galaxy A54 (`SM-A546E`), Android 15, 1080x2340, 450 dpi physical / 421 dpi override. For injected screen-coordinate touches at y=1000, 1050, and 1150, brush centers were y=878, 929, and 1028. These are approximately constant origin shifts, not evidence of scale error. The tested APK was `air.org.psccrevived.pstouchtest`, SHA-256 `dd94bd7ef42c9343b57574a3125edb70bc2692c3c88a1bc2f3ef176c0729f70e`; it had no `AIRAppEntry` inset padding and included the `getBoundHeight/Width()` patch. Hiding navigation moved those stored marks down about 125–126 px. That stored-mark comparison is not an independent touch measurement.

The duplicate `AIRAppEntry` inset padding was removed, but the bug remained. The patch compiled and entered the bundle; that does not establish runtime correctness. Do not add a fixed device offset or treat hidden navigation as a fix.

The failure is confirmed to depend on navigation visibility, but its runtime cause is not yet proven. Detailed bounds, code-path analysis, and test limitations are in the PS Touch Revived [inset investigation](../../psccrevived/experiments/ttpixel-arm64-test/android-smali/insettest/README.md) when both repositories are checked out as sibling directories.

## Other known limitation

The AIR startup splash remains because producing a custom splash requires a commercial AIR SDK license.

Canvas touch alignment with system navigation is unresolved. On a Galaxy A54 (`SM-A546E`, Android 15, 1080x2340, 450 dpi physical / 421 dpi override, three-button navigation), a blank document in the bundled `getBoundHeight/Width()` patch produced a nearly constant 121–122 px upward brush offset at touch y=1000, 1050, and 1150. Hiding navigation moved those same stored marks down about 126 px; the user confirmed new touches align while navigation is hidden. Repeated hidden-state swipes overlapped existing marks and are not independent ink-center measurements. Restoring navigation reproduced the offset and hid the canvas top toolbar. The tested APK (`air.org.psccrevived.pstouchtest`, SHA-256 `dd94bd7ef42c9343b57574a3125edb70bc2692c3c88a1bc2f3ef176c0729f70e`) had no `AIRAppEntry` inset padding. This confirms a navigation-dependent origin shift, but the AIR Stage/input calculation responsible has not been captured.

Two code paths could explain the navigation-dependent shift. `CABDriverAIR` sizes Stage/framework from `Screen.mainScreen.visibleBounds` and on `RESIZE`; `TTEditorView.sizeTo()` recenters by half the size delta, so one 126 px height change accounts for 63 px and cannot explain the full measured offset alone. More directly, AIR `AIRWindowSurfaceView.surfaceChanged()` treats any same-orientation height shrink as a soft-keyboard resize: it calls `nativePerformWindowPanning()` with the height delta and returns before updating the stored surface height or sending the normal resize event. Retained device logcat confirms four 2260→2134 px surface callbacks during navigation restores, each a 126 px shrink after a 2260 px callback. Static analysis of the exact installed `libCore.so` (SHA-256 `ce8b16ab3bfcfb546649469bd71b6baac446aa8770c1b5441d7428785b695bdd`) shows the native pan routine calculates an offset and dispatches it to Java `setScrollTo(int)` through JNI. Java stores it in `mSkipHeightFromTop`, offsets overlay padding, and touch dispatch adds it to local Y. This gives a direct code path from the false keyboard-resize branch to both canvas placement and input coordinates. The internal native symbols are stripped, and runtime logs are still needed to confirm that the branch ran and record the actual offset; the cold-launch case with navigation already visible also remains unverified. The earlier file named as variant A was verified to still contain the getter patch and was discarded. Corrected, instrumented A/B APKs use package `air.org.psccrevived.pstouchtesu`; smali comparison confirms only the getter bodies differ. A is installed and B is ready to replace it under the same package, preserving test app data. Neither has been exercised because the A54 is locked. The current patch is not considered a fix; no device-specific offset should be added. S24 Ultra touch alignment, landscape, gesture navigation, and keyboard/focus behavior remain untested. Detailed bounds and test limitations are in the PS Touch Revived `experiments/ttpixel-arm64-test/android-smali/insettest/README.md`. That analysis identified the false keyboard branch; the IME-gated `surfaceChanged()` fix described in the section above is what the bundle ships now and is confirmed on the A54 cold launch recorded there.
