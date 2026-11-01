# air-touch-64

Android app that converts a supported Photoshop Touch 1.3.7 APK into the PS Touch Revived ARM64 APK using a bundled patch set.

Build:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Run the host patcher (replace `SOURCE.apk` with the supported original APK):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="SOURCE.apk app/src/main/assets/bundle.zip build/out.apk build/test-keys"
```

Run device instrumentation tests:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:connectedDebugAndroidTest
```
