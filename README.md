# Pathfinder

Pathfinder is a native Android AI client built on
[Koog](https://github.com/JetBrains/koog). Koog provides the Kotlin LLM
runtime; Pathfinder adds a focused native Android experience plus tree-based,
branchable sessions ported from [pi](https://pi.dev).

## Direction

- **Build on Koog.** Use Koog's prompt, model, provider-client, streaming, and
  transport APIs instead of maintaining a separate framework inside the app.
  Pathfinder consumes Koog's narrow per-client modules rather than an
  all-providers bundle.
- **Port pi features selectively.** Tree-based session semantics (branching
  and ancestry) is the one ported pi feature; it stays at a tested boundary
  around Koog conversation history.
- **Use Android defaults.** Favor Jetpack Compose, Material 3, dynamic system
  styling, and standard lifecycle, navigation, persistence, and security
  facilities.
- **Stay small and current.** Prefer upstream capabilities and narrow adapters
  over parallel abstractions. Target the latest GrapheneOS and Android
  toolchains rather than carrying broad compatibility machinery.

## Architecture

Pathfinder has three boundaries:

- **Koog runtime** owns prompts and messages, model capabilities, LLM clients,
  streaming, and transport.
- **Pathfinder extensions** connect Android-secured per-provider API keys to
  the runtime and add tree-based sessions: branch selection projects the
  branch's ancestry into Koog conversation history. A small `ChatRuntime`
  seam isolates ViewModels from the Koog implementation.
- **Android app** owns Compose UI, lifecycle, navigation, settings, and
  persistence, exposing runtime state through a conventional MVVM/UDF flow.

This keeps Koog replaceable and upgradeable without hiding it behind another
framework. Pathfinder does not maintain a second agent or provider runtime
alongside Koog. App data is considered disposable during development, so
stored formats track the current implementation and old formats are rejected
rather than migrated.

## Upstream references

Development expects current local checkouts at:

- `~/Projects/koog/` — primary reference for runtime and LLM behavior;
- `~/Projects/pi/` — reference only for the tree-session port (and as history
  to scavenge for future feature designs).

Before changing upstream-derived behavior, compare against current source,
module documentation, and tests rather than remembered APIs. Koog should be
consumed through its public modules; ported pi logic retains source-level
provenance and documents any Android or Koog adaptation. Model and provider
selection uses Koog's model types; app-owned catalog data is limited to
presentation metadata.

## Build

Install a JDK and the Android SDK/Build Tools versions declared by the Gradle
configuration, set `ANDROID_HOME`, then run:

```bash
./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security and diagnostics

Provider API keys are stored behind Android Keystore-backed encryption. API
keys, message content, tool data, and model responses must never be logged.

Operational lifecycle events use the `Pathfinder` Logcat tag:

```bash
adb logcat -v time -s Pathfinder
```
