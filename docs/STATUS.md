# Current state

Snapshot of what exists in `kptmanager`, what was verified and what is still broken.

## Decision

- Product: on-device patcher for PS Touch, Morphe-style flow. The user supplies the original
  Adobe APK; the app never ships it.
- `adt`, FFDec and the NDK cannot run on Android, so the host produces a **bundle** with what the
  phone cannot generate: AIR ARM64 runtime shell, ARM64 extension library, SWF/DEX deltas and
  resource overrides.
- The app applies the bundle to the selected APK with hash checks before and after every step,
  rebuilds the ZIP with alignment, signs with a persistent on-device key and hands off to the
  installer.
- Compiling ActionScript on device was ruled out for now (would need an embedded JVM plus FFDec,
  see `psccrevived/docs/PS_TOUCH_MANAGER_*`).

## Repository layout

```text
engine/                      Kotlin/JVM patch engine, no Android dependencies
  BsPatch.kt                 bsdiff4 (sign/magnitude int64) patch application
  Swf.kt                     CWS inflate/deflate
  AlignedZipWriter.kt        ZIP writer with per-entry alignment
  Signing.kt                 PKCS12 key creation, apksig v2/v3 signing and verification
  PatchEngine.kt             validation, SWF/DEX patches, resource merge, assembly, signing
  Main.kt                    host runner used for validation
app/                         Compose UI
  PatchRepository.kt         asset bundle, SAF import, keystore and output paths
  PatchViewModel.kt          state machine: Home, Running, Done, Failed
  MainActivity.kt            screens, install and save handoff
app/src/main/assets/bundle.zip   generated bundle, 10,341,233 bytes
tools/make_bundle.py         bundle generator and reproducibility self-check
```

## Verified

- Bundle generation from the host build: self-check rebuilds every entry of the final APK from
  source APK + bundle and aborts on any mismatch. Passes.
- Host engine run produced `build/ps-touch-test.apk`:
  - 32,047,234 bytes, SHA-256 `c8cdc0a393ae47f250d100252f0bdc16f8b7b2502b6c0345d0efafe00b932212`
  - 345 entries, contents byte-identical to the host build for every non-signature entry
    (SWF compared decompressed)
  - apksig verification passes for min platform 26
- `apksigner verify` from build-tools 37 fails with `Missing META-INF/MANIFEST.MF` unless
  `--min-sdk-version 26` is passed. Expected: v1 signing is disabled, only v2/v3 are written.

Bundle contents:

- 80 shell entries (AIR ARM64 runtime, manifest, resources.arsc, dex, ANE files, `res/`)
- 13 resource overrides (`about.txt`, `cabmod`, localized `zstrings`, three filter shaders)
- 1 dex patch (`classes.dex`, 98,753 bytes) for the Android 16 insets fix
- 1 native library (`lib/arm64-v8a/libTTPixelExtensionAndroid.so`, 41,176 bytes)
- 1 SWF patch (17,689 bytes, source body -> patched body)

## Known problems

1. **ZIP alignment is wrong for the extension library.** `resources.arsc` and `libCore.so` end up
   aligned, `libTTPixelExtensionAndroid.so` ends at 180 mod 16384. The padding is computed from
   `RandomAccessFile.length()` before the header is written; compute it from the explicit write
   position instead and assert every stored native library after assembly.
2. **The app module was never compiled.** `MainActivity.kt` and `PatchViewModel.kt` are written but
   `:app:assembleDebug` has not run, so compile errors are unknown.
3. **apksig/BouncyCastle on Android is unproven.** The engine ran on the JVM only. Signing and
   certificate generation must be exercised on the device.
4. **`psccrevived` HEAD does not compile.** At `80554cf`,
   `native/src/com/adobe/ttpixel/extension/cameraroll/FnBrowseImages.java:91` calls
   `new FnBrowseImages$1(...)` but the anonymous class no longer exists in the source. The bundle
   was built from a throwaway worktree at `/tmp/pscc-bundle` with the `javac` step removed and the
   checked-in `native/library.jar` reused (identical to the last successful build). Fix this in
   `psccrevived` before rebuilding the bundle.
5. **No release signing for the manager APK.** `app/build.gradle.kts` has no `signingConfig` yet.
6. **No foreground service.** Patching runs in the ViewModel; the app must stay open.
7. `kptmanager` is not a git repository yet, so nothing is committed.
8. The AIR startup splash remains (needs a commercial AIR SDK license), same limitation as
   `psccrevived`.

## Build environment used

```sh
export AIR_SDK=/home/erick/airsdk/AIRSDK_51.5.1
export ANDROID_SDK_ROOT=/home/erick/Android/Sdk
export FFDEC_JAR=/home/erick/Documentos/projetos/psccrevived/tools/jpexs/ffdec-cli.jar
export SOURCE_SWF_SHA256=d941eab1489b086fadeddfa921d0ffd031a69113b058e81482732c9641664f21
export APP_DESCRIPTOR=/tmp/pscc-bundle/experiments/ttpixel-arm64-test/application-android16.xml
export AIR_ADT_TARGET='-target apk-captive-runtime'
export AIR_ADT_ARCH_ARGS='-arch armv8'
export AIR_ADT_SIGNING_ARGS='-storetype JKS -keystore /home/erick/.android/debug.keystore -storepass android -keypass android -alias androiddebugkey'
export BUILD_DIR=/tmp/pscc-bundle-out
export SOURCE_APK=/home/erick/Documentos/projetos/kptmanager/air.com.adobe.pstouchphone_1.3.7-1003007_minAPI14_armeabi-v7a_apkmirror.com.apk
```

Gradle needs Java 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`). JDK 17 is not installed, so the
engine targets 17 with the 21 toolchain.

Host build outputs kept at:

- `/tmp/pscc-bundle-out/base-adt.apk` (adt shell, before insets and extension library)
- `/tmp/pscc-bundle-out/ttpixel-arm64-clean.apk` (final host build)
- `/tmp/pscc-bundle-out/base-insets-unsigned.apk`
- `/tmp/pscc-bundle-build.log`

Generate the bundle:

```sh
python3 tools/make_bundle.py \
  --base /tmp/pscc-bundle-out/base-adt.apk \
  --final /tmp/pscc-bundle-out/ttpixel-arm64-clean.apk \
  --source air.com.adobe.pstouchphone_1.3.7-1003007_minAPI14_armeabi-v7a_apkmirror.com.apk \
  --output app/src/main/assets/bundle.zip
```

Run the engine on the host:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="\
$PWD/air.com.adobe.pstouchphone_1.3.7-1003007_minAPI14_armeabi-v7a_apkmirror.com.apk \
$PWD/app/src/main/assets/bundle.zip \
$PWD/build/ps-touch-test.apk \
$PWD/build/test-keys"
```

## Next steps

1. Fix `AlignedZipWriter` padding and add a post-assembly alignment assertion.
2. Build the app module and fix compile errors.
3. Install on the SM-S928B (`RXCY904E9PM`) and run a full patch job; validate apksig and
   BouncyCastle on Android.
4. Fix `FnBrowseImages.java` in `psccrevived` so the bundle can be rebuilt from a clean checkout.
5. Add release signing for the manager and a one-sentence README with the build commands.
