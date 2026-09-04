# pathfinder

A minimal native Android client for [pi](https://pi.dev). Pathfinder ports the
required pi runtime to Kotlin and keeps the Android application thin.

## Direction

- Pi is the behavioral source of truth for the runtime. Preserve its concepts,
  data shapes, event ordering, provider behavior, session semantics, and error
  handling; do not silently reinterpret or improve them.
- Keep the port selective. Unsupported behavior is the default, and full
  provider parity is not a goal.
- Android is the source of truth for the application layer. Use current Android,
  Jetpack Compose, and Material 3 patterns rather than copying pi's terminal UI.
- Optimize for low maintenance: avoid parallel models, speculative abstractions,
  custom UI primitives, compatibility shims, and unjustified dependencies.
- Development data is disposable. Support only current pi and Pathfinder data
  shapes; reject old formats unless migration is explicitly requested.
- Target the latest GrapheneOS, Android platform, and toolchain. Resolve
  compatibility issues by moving forward rather than downgrading.

## Scope

Port only:

- The `packages/ai` chat runtime needed by APIs registered in
  `ChatApiRegistry`: messages, models, streaming, tool calls, reasoning, usage,
  errors, auth, and shared provider behavior. A provider using an existing API
  may be added cheaply; a new wire protocol or complex auth flow requires an
  explicit decision.
- The classic `packages/agent` Agent and agent loop, plus the retry, compaction,
  conversation-tree, persistence, and recovery behavior used by the app.
- Selected `packages/coding-agent` interaction semantics, implemented as native
  Android UX rather than ported application code.
- The minimum telemetry contract required by those runtime pieces.

Do not port:

- The experimental AgentHarness, lane runtime, durable session substrate, or
  worker architecture. The existing partial port under `agent/harness` and
  `data/sessions/substrate` is slated for removal; do not extend or depend on it.
- Extensions, hooks for third-party customization, Chord plugins/facets, pi
  packages, skills, prompt templates, themes, or resource discovery.
- The TUI, CLI, print/JSON/RPC/SDK modes, terminal keybindings, project trust,
  context-file discovery, or built-in coding tools.
- The remote Chord/client/protocol/server stack, Node session backends, image
  generation, dynamic model stores, or legacy compatibility APIs.

Pathfinder-owned Android code may provide app-specific tools such as web search
and web fetch.

## Working with upstream

Before changing ported behavior, read the corresponding implementation and
package README under `~/Projects/pi/packages/`; do not work from remembered
APIs. Keep upstream module and exported-symbol names where behavior is ported.
Use idiomatic Kotlin only where pi does not dictate the shape. Keep required
Android or Kotlin adaptations narrow, documented, and tested.

Keep provider-opaque data as `JsonElement`; reuse the shared JSON codecs and
accessors rather than introducing serializable mirror DTOs or private helper
families. Streaming uses `Flow`: preserve pi's event contract and always
propagate coroutine cancellation unchanged.

The generated model catalog includes only providers supported end to end and
must not be hand-edited.

## Android architecture

- The platform-neutral runtime owns models, providers, streaming, agent state,
  and conversation semantics.
- The Android layer owns presentation, lifecycle, navigation, input, settings,
  credentials, and platform adapters. It projects runtime state rather than
  duplicating it.
- Use single-activity Compose with MVVM/UDF, immutable `StateFlow` UI state, and
  state-hoisted composables.
- `PathfinderApplication` is the manual composition root. Add a DI framework
  only if the dependency graph clearly justifies it.
- Follow current official Android documentation and prefer stock Material 3
  behavior and components.

## Security

Never log API keys, credentials, message text, or model responses. Secret form
values remain ephemeral, secret-bearing types must redact their string
representations, and persisted credentials stay behind the Android
Keystore-backed boundary.

## Check

```bash
./gradlew spotlessCheck test assembleDebug
```
