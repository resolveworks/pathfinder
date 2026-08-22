# aletheia

A native Android chat app for the [pi](https://pi.dev) agent stack. Kotlin provides
the UI and Android platform integration; the agent runs in embedded QuickJS from a
bundled TypeScript runtime.

## Build

Requires JDK 17+, pnpm, and Android SDK API 37 with Build Tools 37. Set
`ANDROID_HOME`, then run:

```bash
pnpm --dir agent-js install
pnpm --dir agent-js check
pnpm --dir agent-js build
./gradlew test assembleDebug
```

`agent-js` bundles to `app/src/main/assets/agent.js`. The generated asset is ignored
by Git and must be rebuilt after TypeScript changes. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.
