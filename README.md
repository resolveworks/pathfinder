# aletheia

A native Android chat app for the [pi](https://pi.dev) agent stack, written in Kotlin
with Jetpack Compose. The agent runtime awaits a native Kotlin port; until it lands
the chat UI shows an honest unavailable state with the composer disabled.

## Build

Requires JDK 17+ and Android SDK API 37 with Build Tools 37. Set `ANDROID_HOME`, then
run:

```bash
./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Runtime diagnostics

The app writes structured lifecycle events to Logcat under a single tag:

```bash
adb logcat -v time -s Aletheia
```

Logging contains only operational metadata; message text, responses, and API keys are
never logged.
