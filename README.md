# Pathfinder

Pathfinder is a native Android AI client built on
[Koog](https://github.com/JetBrains/koog). Koog provides the Kotlin agent and
LLM runtime; Pathfinder adds a focused native Android experience and selected
capabilities from [pi](https://pi.dev): provider OAuth login and branching,
tree-based sessions.

## Direction

- **Build on Koog.** Use Koog's prompt, model, provider, streaming, tool, agent,
  and feature APIs instead of maintaining a separate framework inside the app.
- **Add pi features selectively.** Port only product-defining behavior that
  Koog does not provide. Pi remains the reference for those isolated features,
  not for the runtime as a whole.
- **Use Android defaults.** Favor Jetpack Compose, Material 3, dynamic system
  styling, and standard lifecycle, navigation, persistence, browser, and
  security facilities.
- **Stay small and current.** Prefer upstream capabilities and narrow adapters
  over parallel abstractions. Target the latest GrapheneOS and Android
  toolchains rather than carrying broad compatibility machinery.

## Architecture

Pathfinder has three boundaries:

- **Koog runtime** owns prompts and messages, model capabilities, LLM clients,
  streaming, tools, agent execution, and runtime features.
- **Pathfinder extensions** connect Android-secured credentials to the runtime
  and add selected pi behavior. OAuth flows remain provider-specific and
  pi-faithful where useful; session storage preserves pi-style ancestry and
  branching while projecting a branch into Koog conversation history.
- **Android app** owns Compose UI, lifecycle, navigation, settings, persistence,
  secure credential storage, and browser/callback integration. It exposes
  runtime state through a conventional MVVM/UDF flow.

This keeps Koog replaceable and upgradeable without hiding it behind another
agent framework, while keeping imported pi behavior at explicit, tested
boundaries.

Pathfinder does not maintain a second agent or provider runtime alongside
Koog. App data is considered disposable during development, so stored formats
track the current implementation without backward-compatibility machinery.

## Upstream references

Development expects current local checkouts at:

- `~/Projects/koog/` — primary reference for runtime and LLM behavior;
- `~/Projects/pi/` — reference only for selected OAuth and tree-session ports.

Before changing upstream-derived behavior, compare against current source,
module documentation, and tests rather than remembered APIs. Koog should be
consumed through its public modules where practical; copied pi logic should
retain source-level provenance and document any Android or Koog adaptation.

Model and provider selection uses Koog's model and capability types. App-owned
catalog data is limited to presentation metadata and does not define a parallel
provider protocol surface.

## Build

Install a JDK and the Android SDK/Build Tools versions declared by the Gradle
configuration, set `ANDROID_HOME`, then run:

```bash
./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security and diagnostics

Provider credentials and OAuth tokens are stored behind Android
Keystore-backed encryption. API keys, tokens, message content, tool data, and
model responses must never be logged. OAuth authorization opens in a system
browser surface rather than an embedded WebView.

Operational lifecycle events use the `Pathfinder` Logcat tag:

```bash
adb logcat -v time -s Pathfinder
```
