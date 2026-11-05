# Project status

## Product

Air Touch 64 is an Android patcher for Photoshop Touch. The user imports a patch bundle, selects the original APK, and the app produces the PS Touch Revived ARM64 APK. Neither the original Adobe APK nor the patch bundle is stored in this repository.

The app supports Android 8.0 (API 26) and newer, and has Portuguese and English strings. It accepts only the source APK whose SHA-256 is listed in the bundle manifest:

```text
c57e409493f46f4b9404678a777e23cee7fedcf921427435f4767efda5823a85
```

This is the Photoshop Touch 1.3.7 variant used to create the current bundle. Other repackaged or region-specific APKs are rejected even if they report the same version. The app links to APKMirror and APKPure for the source APK but does not host or verify those downloads.

## App flow

1. The user imports an `.at64` bundle through Android's document picker. The app validates the AT64 header, copies the bundle to `filesDir/bundle/bundle.at64`, and stores the display name separately.
2. The user picks the source APK. The app copies it to cache and checks its SHA-256 and required entries against the bundle header.
3. The engine decrypts the bundle using key material derived from the source APK, applies the SWF, DEX and resource patches, then assembles the AIR ARM64 shell and native libraries.
4. The APK is aligned, signed with a persistent per-install key, and verified on device.
5. The user opens the Android package installer or saves the APK through the system document picker.

The bundle is imported manually and is not downloaded automatically. Distribution is external; see `README.md`. Importing another bundle re-inspects the selected source APK. Patching runs in the ViewModel, not a foreground service; keep Air Touch 64 open while patching.

The signing key is stored in the app's private files and is removed when the app is uninstalled. APKs produced by different installations have different signing certificates and cannot update one another in place. The generated APK uses a different key from Adobe's original APK, so it installs as a separate app. The source Android package installer may require the user to allow installs from Air Touch 64.

## Project pieces

- `app/`: Compose UI, bundle and APK import, install handoff and on-device orchestration.
- `engine/`: Kotlin patch engine shared by Android and the host runner. It reads and decrypts `.at64` bundles, validates inputs and patch outputs, writes aligned ZIP entries, signs and verifies the output.
- `engine/src/main/kotlin/dev/airtouch64/engine/BundleCrypto.kt`: reads AT64 formats 2 and 3; new bundles use outer format 3 and inner manifest version 2. The JSON header authenticates a ZIP payload encrypted with AES-GCM. The key comes from SHA-512 over the source APK's SWF, `assets/CABConfig.xml` and `classes.dex`, then PBKDF2-HMAC-SHA256 with 120,000 iterations and a 256-bit key.
- `tools/make_bundle.py`: host-side bundle generator. It applies declared resource transformations and source copies in its self-check, and rejects unclassified resource changes. The only full resource override is project `about.txt`. An `.at64` or `.enc` output is encrypted; a plain `.zip` output is not accepted by the app.
- No patch bundle is committed. The app only uses a bundle imported by the user.

## Build and run

The Gradle wrapper uses Gradle 8.13. The project targets Java 17 bytecode; Java 21 is the configured local runtime used for the commands below.

Build the debug app:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Run the host patcher with a supported source APK and an `.at64` bundle:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="SOURCE.apk BUNDLE.at64 build/out.apk build/test-keys"
```

Run the Android instrumentation test. It expects the same supported source APK at `/data/local/tmp/pstouch-source.apk` and a bundle named `PS Touch Revived ARM64` imported in the app:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:connectedDebugAndroidTest
```

The release build reads signing values from an untracked `keystore.properties` file or the `AIRTOUCH_RELEASE_*` environment variables. See `keystore.properties.example`. Without release signing configured, the release APK is unsigned.

To regenerate the bundle, build the AIR shell and final APK on a host with AIR SDK, FFDec, NDK and `bsdiff4` available, then run:

```sh
python3 tools/make_bundle.py --base BASE.apk --final FINAL.apk --source SOURCE.apk --output BUNDLE.at64
```

The `--base`, `--final` and `--source` APKs are host build inputs and are not stored in this repository. The generated bundle is also external; import it in the app instead of committing it.

## Verified behavior

Recorded from the latest validation run:

- Bundle self-check reproduced every non-signature entry of a 345-entry reference APK reconstructed from the known-good bundle and supported source APK.
- Host engine output from the new format 3 bundle matched all 345 reference entries, passed APK signature verification and the connected Android instrumentation test on an API 35 x86_64 emulator.
- All eight EGL shader rendering tests passed using shaders reconstructed from the source APK.
- The patched APK launched on the API 35 emulator. The About screen showed the expected project text in English, Portuguese and Japanese, including the project-source and third-party notice labels.
- `python3 -m unittest discover -s tools`, `:engine:test` and `:app:assembleDebug` passed.
- A previous build's full patch pipeline, signing and verification passed on a Galaxy S24 Ultra running Android 16; that device check predates the resource transformation bundle.

The bundle used for these checks is external to the repository.

## Canvas touch offset with system navigation

AIR's `AIRWindowSurfaceView.surfaceChanged()` treats every same-orientation height shrink as a soft keyboard resize. It queues `nativePerformWindowPanning(delta)`, sets `mSurfaceChangedForSoftKeyboard` and returns before updating `mHt` or sending the `ON_SIZE_CHANGED` event. When the navigation bar appears, the surface shrinks by the bar height, so the Stage keeps the taller size while the surface is shorter and drawing ends up offset from the touch point. Hiding the navigation bar made the offset disappear because the false keyboard branch never ran.

The bundle's `classes.dex` patch gates that branch on the IME inset. `surfaceChanged()` asks `WindowInsets.getInsets(WindowInsets.Type.ime()).bottom` (API 30+) and only takes the keyboard panning path when an IME is actually visible. Otherwise it falls through to the normal resize path, which updates `mHt`, dispatches the size-changed event and resets `mSkipHeightFromTop`. Below API 30 and when insets are unavailable, the previous behavior is kept. `tools/patch_bundle_dex.py` regenerates the bundle delta from the patched smali; the canonical patch lives in the PS Touch Revived `experiments/ttpixel-arm64-test/patch-android-insets.py`.

Status: confirmed on a Galaxy A54 (`SM-A546E`), Android 15, 1080x2340, 421 dpi override, three-button navigation. A cold launch with the navigation bar visible keeps the `Ps Touch` top bar and newly drawn strokes land under the finger. Before the fix, the brush landed about 122 px above the touch point and the toolbar was hidden; leaving the app and returning from recents repaired it, consistent with the new surface taking the normal resize path.

Untested: gesture navigation, landscape, the IME/keyboard path and the S24 Ultra.

## Known limitations

- The AIR startup splash remains; removing it requires a commercial AIR SDK license.
- Camera Fill does not work and produces a white render.
- Directional Light does not work correctly.
- Watercolor produces odd results.
- Setting Comic or Posterizer to values near 1 can break the photo.
- Canvas touch with system navigation is confirmed only for the A54 cold-launch case; gesture navigation, landscape, the IME path and the S24 Ultra remain untested.
