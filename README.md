# Air Touch 64

Air Touch 64 patches a supported Photoshop Touch APK into an ARM64 build for modern Android devices. The app runs the patching process on the device and lets you install or save the resulting APK.

## Disclaimer

Air Touch 64 is an independent community project. It is not affiliated with, endorsed by, or sponsored by Adobe Inc. Adobe, Photoshop, and Photoshop Touch are trademarks of Adobe Inc. This project does not claim ownership of Photoshop Touch.

The original Photoshop Touch APK is not included. You must provide your own copy. Links in the app open third-party sites; Air Touch 64 is not affiliated with or responsible for those sites or their content.

## What it does

The app includes a patch bundle containing the ARM64 AIR runtime shell, native library, resource updates, and binary patches. After you choose the original APK, Air Touch 64:

1. Checks that the APK matches the supported source and contains the expected files.
2. Applies the SWF and DEX patches and replaces selected resources.
3. Assembles and aligns the ARM64 APK, then signs and verifies it on the device.
4. Opens the Android installer or saves the APK to a location you choose.

The bundle supports one Photoshop Touch 1.3.7 APK variant. Other builds, repackaged APKs, and regional variants may be rejected even if they show the same version number.

The generated APK is signed with a key created on your device. It cannot update the Adobe-signed original; uninstall the original before installing the patched app. The key is kept in Air Touch 64's private storage and is removed if you uninstall Air Touch 64. Keep the app open while patching.

The app requires Android 8.0 (API 26) or newer. The patched build has been tested on Android 16, including on a Samsung Galaxy S24 Ultra and Galaxy A54. The default AIR startup splash remains because replacing it requires a commercial AIR SDK license.

## Get the original APK

The app provides links to these third-party download pages:

- [Photoshop Touch 1.3.7 on APKMirror](https://www.apkmirror.com/apk/adobe/adobe-photoshop-touch-phone/ps-touch-1-3-7-release/)
- [Photoshop Touch on APKPure](https://apkpure.net/br/adobe-photoshop-touch/air.com.adobe.pstouchphone/download)

The app checks the file's SHA-256 against the bundle. A download from either site may not match the supported variant, in which case the app will show it as incompatible.

## Build

Requirements: JDK 21, Android SDK with platform 36, and the Android build tools. Build the debug app with the Gradle wrapper:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Run the host patcher

The Kotlin engine can also apply the bundle on a host. Replace `SOURCE.apk` with the supported original APK:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="SOURCE.apk app/src/main/assets/bundle.zip build/out.apk build/test-keys"
```

## Tests

Run the Android instrumentation test on a connected device or emulator. The test reads the source APK from `/data/local/tmp/pstouch-source.apk`:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:connectedDebugAndroidTest
```

## Releases

See [GitHub Releases](https://github.com/keyarr/air-touch-64/releases). Release builds use a private keystore configured in `keystore.properties` or through the `AIRTOUCH_RELEASE_*` environment variables.
