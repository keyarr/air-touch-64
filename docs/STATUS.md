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
app/src/main/assets/bundle.zip   generated bundle, 14,121,587 bytes
tools/make_bundle.py         bundle generator and reproducibility self-check
```

## Verified

- Bundle generation from the host build: self-check rebuilds every entry of the final APK from
  source APK + bundle and aborts on any mismatch. Passes.
- Host engine run produces signed, aligned APK:
  - 345 entries, contents byte-identical to the host build for every non-signature entry
  - ZIP alignment verified: shared libraries aligned to 16 KB boundaries, stored entries to 4 bytes
  - apksig verification passes (v1, v2 and v3 schemes enabled)
- Android module compiles and packages cleanly.
- Full on-device patching pipeline verified on SM-S928B (Galaxy S24 Ultra, Android 16) via
  `DevicePatchTest`:
  - Bundle read from APK asset
  - BouncyCastle certificate generation and PKCS12 KeyStore on Android runtime
  - Raw SWF bspatch, DEX bspatch, resource merge, 16 KB aligned ZIP write
  - apksig v1/v2/v3 signing and verification on device
- On-device launch verified: patched APK installed and run on SM-S928B; Harman AIR runtime
  package integrity check (`AIRRuntimeCheck`) passes without "package may have been modified"
  warning because the SWF patch operates directly on the compressed CWS file, preserving the
  exact bytes signed in `META-INF/AIR/license.txt`.
- Native extension library includes particle blending alpha accumulation fix (`da3f68d`).
- `psccrevived` compiles with `FnBrowseImages.java` anonymous `Runnable` restored.

## Known problems

1. **No foreground service.** Patching runs in the ViewModel; the app must stay open during the run.
2. The AIR startup splash remains (needs a commercial AIR SDK license), same limitation as
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

1. Optional foreground service for background patch jobs.
2. User-facing release build and distribution.
