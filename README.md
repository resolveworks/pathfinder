# aletheia

Native Android chat app around the [pi](https://pi.dev) agent stack.

## Build

Android Studio is not required. Install a JDK (17 or newer) and Android SDK API 37 with Build Tools 37, then set `ANDROID_HOME` to the SDK directory.

```bash
./gradlew assembleDebug
./gradlew test
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Status: scaffolding. See `AGENTS.md` for architecture and scope.
