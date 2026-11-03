# Air Touch 64

Air Touch 64 turns a supported Photoshop Touch 1.3.7 APK into an ARM64 build for modern Android devices. It applies the patches on-device, then lets you install or save the signed APK.

<p align="center">
<img width="280" alt="airtouch" src="https://github.com/user-attachments/assets/a880ffbf-7c64-458b-99ee-19cdad29edc6" />
</p>

## Community

Join the [Telegram channel](https://t.me/+_VuvftJw4gY2NmUx) for bundle info and project updates.

## How it works

Choose a patch bundle and your original Photoshop Touch APK. The app checks the APK's SHA-256 and required files against the bundle, applies the SWF, DEX, resource, and ARM64 runtime updates, then assembles and signs the result. For bundle info, join the [Telegram channel](https://t.me/+_VuvftJw4gY2NmUx) and import the bundle in the app.

Only the source variant listed in the bundle is supported. Other regional releases, repackaged APKs, or files with a different hash are rejected, even if they report version 1.3.7. The original APK is not included. The app links to third-party download pages for [APKMirror](https://www.apkmirror.com/apk/adobe/adobe-photoshop-touch-phone/ps-touch-1-3-7-release/) and [APKPure](https://apkpure.net/br/adobe-photoshop-touch/air.com.adobe.pstouchphone/download), but does not host or verify their downloads in advance.

The output APK is signed with a key generated on your device, so it cannot update an Adobe-signed installation. Uninstall the original app before installing the patched APK. The signing key is stored in Air Touch 64's private app data and is removed if you uninstall Air Touch 64. Keep the app open while patching.

Air Touch 64 is an independent community project and is not affiliated with, endorsed by, or sponsored by Adobe Inc. Adobe, Photoshop, and Photoshop Touch are trademarks of Adobe Inc.

## Requirements

- Android 8.0 (API 26) or newer to run the patcher.
- JDK 17 and Android SDK Platform 36 to build the app.

The patched app has been tested on Android 16, including Samsung Galaxy S24 Ultra and Galaxy A54.

## Known limitations

- The AIR SDK flashbang appears when the app opens. Removing it requires a commercial AIR SDK license, which this project does not have.
- Setting Comic or Posterizer to values near 1 can break the photo.
- Camera Fill does not work and produces a white render.
- Directional Light does not work correctly.
- Watercolor produces odd results; it has not been confirmed whether this effect is commonly used.

## Build

Build the debug APK with the Gradle wrapper:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

Run the host-side unit tests:

```sh
./gradlew test
```

The Android instrumentation test runs the full patch pipeline on a connected device or emulator. Import a patch bundle in the app first, then push a supported source APK to the device:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
./gradlew :app:connectedDebugAndroidTest
```

## Host patcher

The Kotlin engine can also patch an APK on the host. Pass a supported source APK, an `.at64` bundle, an output path, and an optional directory for the signing key:

```sh
./gradlew :engine:run --args="SOURCE.apk bundle.at64 build/out.apk build/test-keys"
```

## Release signing

Release builds use a private keystore configured in `keystore.properties` or through the `AIRTOUCH_RELEASE_*` environment variables. See `app/build.gradle.kts` for the property names. Never commit the keystore or its passwords.
