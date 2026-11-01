# kptmanager

On-device patcher that turns a user-supplied Photoshop Touch APK into the PS Touch Revived ARM64
build, using a prebuilt patch bundle.

Build:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Generate the bundle (needs the host build artifacts from `psccrevived`):

```sh
python3 tools/make_bundle.py --base BASE.apk --final FINAL.apk --source SOURCE.apk --output app/src/main/assets/bundle.zip
```

Run the engine on the host:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :engine:run --args="SOURCE.apk app/src/main/assets/bundle.zip build/out.apk build/test-keys"
```

Current state and open problems: `docs/STATUS.md`.
