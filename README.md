# aletheia

Native Android chat app around the [pi](https://pi.dev) agent stack.

## Build

Install a JDK (17 or newer) and Android SDK API 37 with Build Tools 37, then set `ANDROID_HOME` to the SDK directory.

Build the generated QuickJS asset first, then the Android app:

```bash
pnpm --dir agent-js install
pnpm --dir agent-js check
pnpm --dir agent-js build
./gradlew assembleDebug
./gradlew test
```

The generated `app/src/main/assets/agent.js` is ignored by Git. Rebuild it after changing `agent-js/`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Testing

Testing is intentionally minimal. The current suite consists of fast, plain JVM tests for application logic and runs with `./gradlew test`.

Status: scaffolding. See `AGENTS.md` for architecture and scope.
