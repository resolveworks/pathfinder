# aletheia

A native Android chat app for the [pi](https://pi.dev) agent stack, written in Kotlin
with Jetpack Compose. The native agent runtime is wired in: configure any of the
bundled OpenAI Chat-Completions providers (26 providers, 663 models generated from
pi's catalog), manage per-provider credentials, stream chat responses, and switch
between persistent sessions. Agent tools, including `web_search` and `web_fetch`,
are out of scope for the MVP.

## Build

Requires JDK 17+ and Android SDK API 37 with Build Tools 37. Set `ANDROID_HOME`, then
run:

```bash
./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Model catalog

`app/src/main/assets/models-catalog.json` is generated — never hand-edited — from a
local pi checkout:

```bash
node tools/generate-model-catalog.mjs          # PI_REPO_DIR or ~/Projects/pi
```

The script runs pi's `generate-models.ts` (models.dev et al.), keeps the
openai-completions providers, and merges each provider's hand-curated identity
(display name and API-key auth prompts, mirroring pi's hand-written
`providers/*.ts`). Fields pi solves with environment variables (API keys,
Cloudflare account/gateway ids) become credential inputs in the app and are
stored per provider in the Android Keystore-backed credential store.

## Runtime diagnostics

The app writes structured lifecycle events to Logcat under a single tag:

```bash
adb logcat -v time -s Aletheia
```

Logging contains only operational metadata; message text, responses, and API keys are
never logged.
