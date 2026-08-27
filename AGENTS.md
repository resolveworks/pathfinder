# pathfinder

A minimal native Android client for [pi](https://pi.dev). Pathfinder ports the
relevant pi runtime to pure Kotlin and keeps the Android application around it
as thin as possible.

## Direction

- Pi is the behavioral source of truth. Mirror the current implementation in
  `~/Projects/pi` as closely as Kotlin and Android allow: preserve concepts,
  data shapes, event ordering, provider behavior, session semantics, and error
  handling rather than designing Pathfinder-specific equivalents.
- Port selectively, but faithfully. Minimal scope means exposing a focused set
  of pi capabilities, not creating simpler competing semantics. Add capability
  by porting it from pi when possible.
- Android is the source of truth for the application layer. Use current
  platform guidance, Jetpack Compose, Material 3, and stock Android components
  and interactions. Translate pi behavior into native Android UX; do not copy
  terminal UI literally or build a custom design system where Android already
  provides the answer.
- Optimize for low maintenance. Avoid parallel domain models, speculative
  abstractions, bespoke UI primitives, compatibility shims, and dependencies
  whose value does not justify their upkeep.
- Treat Pathfinder app data as disposable during development. Implement only pi's
  current data shapes and the app's current storage formats; reject old formats
  instead of adding migrations or backward-compatibility paths unless explicitly
  requested.
- Target the latest GrapheneOS release and the latest Android platform and
  toolchain. Keep Kotlin, AGP, Compose, and AndroidX current; move forward to
  resolve compatibility problems rather than downgrading.

## Sources of truth

Before changing ported behavior, read the corresponding source and package
README under `~/Projects/pi/packages/`; do not work from remembered APIs. The
main mappings are:

- `works.resolve.pathfinder.ai` mirrors `packages/ai`.
- `works.resolve.pathfinder.agent` mirrors `packages/agent`.
- Conversation and session-tree behavior mirrors pi's session semantics.
- Android UI behavior may adapt useful interaction semantics from
  `packages/coding-agent`, while remaining native Android UI.

Keep symbol-level provenance in KDoc and tests for ported logic. When Android or
Kotlin requires a divergence, make the adaptation at the narrowest boundary,
document the upstream behavior and reason for the divergence, and test it. Do
not silently “improve” or reinterpret pi while porting it.

Follow current official Android documentation for platform APIs and UI
patterns. Prefer documented current APIs over remembered ones.

## Architecture

- The native runtime owns models, providers, streaming, agent state, and
  conversation semantics. Keep it platform-neutral Kotlin where practical.
- The Android layer owns presentation, lifecycle, navigation, input, settings,
  secure credential storage, and platform adapters. It should project runtime
  state rather than duplicate runtime behavior.
- UI follows single-activity Compose with MVVM/UDF: ViewModels expose immutable
  state through `StateFlow`; composables stay state-hoisted apart from
  ephemeral UI state and forward user intents.
- Prefer Material 3 defaults, dynamic system color, edge-to-edge layout, and
  standard navigation and back behavior. Custom UI should exist only where the
  chat interaction has no adequate platform component.
- `PathfinderApplication` is the manual composition root. Keep the dependency
  graph direct; add a DI framework only if the graph's complexity clearly
  justifies its maintenance cost.

The model catalog asset is generated from pi and must never be hand-edited.

## Security

Never log API keys, credential values, message text, or model responses. Secret
form values may live only in ephemeral UI memory, and persisted credentials
must remain inside the Android Keystore-backed credential boundary.

## Commands

```bash
./gradlew test assembleDebug
```
