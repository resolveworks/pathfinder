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
keys, OAuth values, message content, tool data, model responses, URLs, provider
payloads, and exception messages must never be logged.

Debug APKs emit handled-failure and boundary lifecycle diagnostics under the
`Pathfinder` Logcat tag. Entries use stable event identifiers and constrained
metadata (HTTP status, exception type chain, and the first Pathfinder stack
frame); raw `Throwable` values are never handed to Logcat. For runtime chat
requests the HTTP status is harvested from Koog's `KoogHttpClientException`
inside the cause chain. Non-debug APKs do not install a logging backend, and
Pathfinder does not persist or upload logs.

Instrumentation follows a lowest-layer-logs rule: the layer that first
observes a failure emits the event, and higher layers that merely map it to a
user-safe message do not duplicate it. Event areas mirror the code layout:
`codex.*` (OAuth sign-in and token refresh), `chat.*` (prompt execution in the
Koog runtime), `session.*` (transcript persistence), `credential.*`
(Keystore-backed credential storage), `ui.*` (ViewModel boundary failures
with no instrumented lower layer, e.g. settings writes).

```bash
adb logcat -v time -s Pathfinder:I
```
