# Air Touch 64 converts the supported Photoshop Touch 1.3.7 APK into an ARM64 build; the app embeds its audited local-SWF patch bundle.

Build the app:

```sh
./gradlew :app:assembleDebug
```

Run host tests:

```sh
./gradlew test
python3 -m unittest discover -s tools
```

Run the device pipeline test with the supported source APK:

```sh
adb push SOURCE.apk /data/local/tmp/pstouch-source.apk
./gradlew :app:connectedDebugAndroidTest
```
