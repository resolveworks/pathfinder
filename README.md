# Pathfinder

Pathfinder is a native Android research agent: a chat app that lets a language
model search the web and read pages to answer. It talks directly to the model
providers you configure — there is no Pathfinder server in between.

## Install

- Requires Android 14 or newer.
- Download the signed APK from the latest
  [release](https://github.com/resolveworks/pathfinder/releases).
- [Obtainium](https://github.com/ImranR98/Obtainium) can track releases for
  updates.

## Features

- **Providers**: sign in with API keys or existing subscriptions — Anthropic
  (Claude Pro/Max), OpenAI (ChatGPT Plus/Pro), GitHub Copilot, Kimi, xAI
  (Grok/X Premium), OpenRouter — or point it at most major providers and
  aggregators. Switch models mid-conversation.
- **Tools**: `web_search` (Brave Search API — bring your own key) and
  `web_fetch` (renders the page, returns its readable content as markdown).
- **Sessions**: stored on device as plain JSONL. Branch any turn into a new
  line of conversation, search across sessions, export or share them.
- **Runtime**: automatic retries, automatic compaction when a conversation
  outgrows the model's context window, reasoning display with per-model
  thinking-level control, and a custom system prompt (persona).

## Privacy

No analytics, crash reporting, or telemetry. Traffic goes only to the
providers you configure, to Brave when search is set up, and to pages the
agent fetches. Credentials are stored encrypted behind the Android Keystore
and never leave the device except to authenticate with their provider.

## How it's built

The chat runtime is a selective Kotlin port of [pi](https://pi.dev)'s agent
runtime — messages, streaming, tool calls, reasoning, usage, auth and OAuth,
retry, compaction, and session persistence and recovery — in platform-neutral
Gradle modules (`ai`, `agent`, `coding-agent`, `telemetry`) mirroring pi's
package layout. pi is the behavioral source of truth for everything ported;
the model catalog is generated from a pi checkout. The Android layer
(Jetpack Compose, Material 3) and the web tools are Pathfinder's own.

To build, install the JDK and Android SDK versions declared by the Gradle
configuration, set `ANDROID_HOME`, then run:

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## License

GPL-3.0. The ported runtime derives from pi (MIT); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
