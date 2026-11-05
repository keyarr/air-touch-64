# Project status

## Product

Air Touch 64 is an Android patcher for Photoshop Touch. It embeds `app/src/main/assets/bundle.at64`, selects the original APK, and produces the PS Touch Revived ARM64 APK. The original Adobe APK is not stored in this repository.

The app supports Android 8.0 (API 26) and newer, and has Portuguese and English strings. It accepts only the source APK whose SHA-256 is listed in the bundle manifest:

```text
c57e409493f46f4b9404678a777e23cee7fedcf921427435f4767efda5823a85
```

This is the Photoshop Touch 1.3.7 variant used to create the current bundle. Other repackaged or region-specific APKs are rejected even if they report the same version. The app links to APKMirror and APKPure for the source APK but does not host or verify those downloads.

## App flow

1. The app loads the embedded `.at64` bundle into `filesDir/bundle/bundle.at64` and validates its AT64 header.
2. The user picks the source APK. The app copies it to cache and checks its SHA-256 and required entries against the bundle header.
3. The engine decrypts the bundle using key material derived from the source APK, applies the local SWF transformation, DEX and resource patches, then assembles the AIR ARM64 shell and native libraries.
4. The APK is aligned, signed with a persistent per-install key, and verified on device.
5. The user opens the Android package installer or saves the APK through the system document picker.

Patching runs in the ViewModel, not a foreground service; keep Air Touch 64 open while patching.

The signing key is stored in the app's private files and is removed when the app is uninstalled. APKs produced by different installations have different signing certificates and cannot update one another in place. The generated APK uses a different key from Adobe's original APK, so it installs as a separate app. The source Android package installer may require the user to allow installs from Air Touch 64.

## Project pieces

- `app/`: Compose UI, bundle and APK import, install handoff and on-device orchestration.
- `engine/`: Kotlin patch engine shared by Android and the host runner. It reads and decrypts `.at64` bundles, validates inputs and patch outputs, writes aligned ZIP entries, signs and verifies the output.
- `engine/src/main/kotlin/dev/airtouch64/engine/BundleCrypto.kt`: reads AT64 formats 2 through 4. The embedded bundle uses outer format 4 and inner manifest version 3. The authenticated JSON header accompanies a ZIP payload encrypted with AES-GCM.
- `tools/make_bundle.py`: host-side generator. Release runs require `--require-local-swf`; experimental SWF deltas need the explicit `--allow-swf-delta` flag. Generation verifies the audited transform hashes and reconstructs every final APK entry before writing. `.at64` output is encrypted.
- `app/src/main/assets/bundle.at64`: embedded app bundle. Root `bundle.at64` is an ignored generated distribution copy.

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

Run the Android instrumentation test. It uses the embedded bundle and expects the supported source APK at `/data/local/tmp/pstouch-source.apk`:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:connectedDebugAndroidTest
```

The release build reads signing values from an untracked `keystore.properties` file or the `AIRTOUCH_RELEASE_*` environment variables. See `keystore.properties.example`. Without release signing configured, the release APK is unsigned.

To regenerate the bundle, build the AIR shell and final APK on a host with AIR SDK, FFDec, NDK and `bsdiff4` available, then run:

```sh
python3 tools/make_bundle.py --base BASE.apk --final FINAL.apk --source SOURCE.apk --output BUNDLE.at64 --require-local-swf
```

The `--base`, `--final` and `--source` APKs are host build inputs and are not stored in this repository.

## Verified behavior

Recorded from the latest validation run:

- The audited SWF changes are `TTApplication.applicationWillRun` startup handling, `TTSyncEngine.initialize` local sync selection, and brush color/opacity handling in `TTBrushTool` and `TTPaintTool`. The reference comparison found no camera or splash changes for this supported source. ABC serialization differences do not imply a behavior difference; the transform is gated by its complete output hash and the method-level selectors.
- Startup keeps the Stage reference on the expected namespace and replaces the startup expression in place. Sync forces `TTExtensionWrapper.instance.isAvailable()` false so SyncFile stays local. Painting accumulates opaque white coverage, then applies the brush color and opacity when finalizing; eraser and pen-eraser branches remain intact. Camera permission and app-splash edits belong to other source variants, and the Android insets fix is a DEX change.

- `--require-local-swf` regenerated the bundle from the supported source APK, approved ARM64 base and final APK. Reconstruction matched all 353 non-signature entries.
- The embedded bundle uses manifest version 3 (`local-transform-v1`) and outer AT64 version 4. Its 82-entry payload has no `patches/swf.bdf` or main `assets/TTPixel.swf`; shell and extension SWFs remain.
- Host patching and the API 35 device test both verified the output SWF SHA-256 `496c3e5009b94ebabecf57beb3f5a0dfade967b8ba6e626cadd2446d0bd538c5`. The device test passed signature verification and the 353-entry check.
- All eight EGL shader rendering tests passed using shaders reconstructed from the source APK.
- All four transformed shaders compiled and drew through GLES 2 on the API 35 emulator using the generated APK. Comic, Posterize Color and Threshold also previewed on a transparent canvas in the app; Replace Color is categorized as `generic` and is not exposed in the filter menu.
- The patched APK launched on the API 35 emulator. The About screen showed the expected project text in English, Portuguese and Japanese, including the project-source and third-party notice labels.
- `python3 -m unittest discover -s tools`, `:engine:test` and `:app:assembleDebug` passed.
- A previous build's full patch pipeline, signing and verification passed on a Galaxy S24 Ultra running Android 16; that device check predates the resource transformation bundle.

The current embedded bundle is `app/src/main/assets/bundle.at64`.

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
