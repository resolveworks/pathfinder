# aletheia

A native Android chat app for the [pi](https://pi.dev) agent stack, written in Kotlin
with Jetpack Compose. The native Z.AI agent runtime is wired in: configure a provider,
model, and API key; stream chat responses; and switch between persistent sessions.
Agent tools, including `web_search` and `web_fetch`, are out of scope for the MVP.

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
