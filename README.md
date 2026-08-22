# aletheia

Native Android chat app around the [pi](https://pi.dev) agent stack.

## Build

Install a JDK (17 or newer) and Android SDK API 37 with Build Tools 37, then set `ANDROID_HOME` to the SDK directory.

```bash
./gradlew assembleDebug
./gradlew test
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The pi runtime is a bundled QuickJS asset. The generated asset is committed so ordinary Android builds do not require Node.js. After changing `agent-js/`, rebuild it with pnpm:

```bash
cd agent-js
pnpm install
pnpm run check
pnpm run build
```

## Testing

Testing is intentionally minimal. The current suite consists of fast, plain JVM tests for application logic and runs with `./gradlew test`.

Status: scaffolding. See `AGENTS.md` for architecture and scope.
