# pathfinder

A native Android AI client built on [Koog](https://github.com/JetBrains/koog).
Pathfinder uses Koog for its Kotlin agent and LLM foundation, adds a small set
of selected capabilities from [pi](https://pi.dev)—tree-based branching
sessions—and keeps the Android application layer thin.

## Direction

- **Koog is the runtime foundation.** Reuse Koog's prompt, message, model,
  provider-client, streaming, tool, agent, feature, and lifecycle abstractions
  instead of maintaining Pathfinder equivalents. Follow current Koog behavior
  and public APIs rather than maintaining a parallel Pathfinder runtime.
- **Extend Koog; do not fork it inside the app.** Prefer Koog modules and
  extension points. Fill product-specific gaps with narrow adapters or
  features. If a generally useful capability is missing, consider an upstream
  Koog contribution before copying framework internals into Pathfinder.
- **Port from pi selectively.** Pi is the source of truth only for capabilities
  explicitly chosen from it: branching session semantics. Keep those ports
  isolated at the edge of Koog; they must not grow into a second model,
  provider, or agent runtime.
- **Android is the application source of truth.** Use current platform
  guidance, Jetpack Compose, Material 3, and stock Android components and
  interactions. Adapt runtime behavior to native Android UX rather than
  copying any terminal interface.
- **Optimize for low maintenance.** Avoid parallel domain models, speculative
  abstractions, bespoke UI primitives, compatibility shims, and dependencies
  whose value does not justify their upkeep.
- **Treat development data as disposable.** Implement only current Koog/pi
  shapes and the app's current storage formats. Reject old formats rather than
  adding migrations unless explicitly requested.
- **Track the platform.** Target the latest GrapheneOS release and current
  Android, Kotlin, AGP, Compose, and AndroidX versions. Move forward to resolve
  compatibility issues rather than downgrading.

## Sources of truth

Use the source that owns the behavior being changed; do not work from
remembered APIs.

### Koog

The primary upstream checkout is `~/Projects/koog/`. Before changing runtime,
prompt, model, provider, tool, streaming, or agent behavior, read the relevant
Koog source, tests, root `README.md`, and module `Module.md`. Important areas
include:

- `prompt/` for messages, models, capabilities, prompt execution, and provider
  clients;
- `agents/agents-core/` for agent execution, graph strategies, lifecycle, and
  feature integration;
- `agents/agents-tools/` for tool contracts and registries;
- `agents/agents-features/` for reusable runtime features;
- `http-client/` for transport integration.

Use released Koog artifacts and public APIs where practical. The local checkout
is the behavioral and API reference, not code to copy wholesale. When an
Android constraint requires a divergence, keep it in a narrow platform adapter
and test it.

### pi

The pi checkout at `~/Projects/pi/` is authoritative only for selected pi
features. Before changing one, read its current source, package README, and
tests. The selected features are:

- conversation branching, ancestry, and session-tree behavior from
  `packages/coding-agent` (and small pi-derived presentation utilities such as
  the markdown renderer and telemetry contracts, which cite their sources in
  KDoc).

Keep symbol-level pi provenance in KDoc and tests for translated logic. Preserve
the selected behavior's event ordering, data semantics, cancellation, and error
handling unless Android or Koog requires a documented boundary adaptation.
Do not use pi as the default reference for capabilities Koog already owns.

### Precedence

Koog owns runtime contracts; pi owns only an explicitly selected port; Android
owns platform behavior; Pathfinder owns product policy and glue. Resolve
conflicts at an adapter boundary instead of modifying one layer to impersonate
another.

## Implementation boundaries

- Runtime and provider code uses Koog types end to end; Pathfinder does not
  maintain alternative message, model, event, streaming, tool, or agent-loop
  contracts.
- Tree-session behavior is a Pathfinder session layer around Koog history;
  branching semantics do not leak into Koog's core runtime.
- Model and provider selection is expressed with Koog models and capabilities.
  App-owned catalog data may add presentation metadata, but not parallel
  protocol behavior.
- Compatibility bridges and dual runtime stacks are not part of the
  architecture.

## Naming and style

- Koog-backed code uses Koog concepts and types directly. Do not create
  Pathfinder aliases or wrappers merely to preserve names from the old pi
  port.
- A selected pi port keeps upstream exported names where that improves
  provenance. Translate TypeScript with the conventions in
  `app/src/main/kotlin/works/resolve/pathfinder/AGENTS.md`.
- Pathfinder-owned and Android code follows current idiomatic Kotlin: data
  classes, sealed types, nullability, coroutines, and standard library APIs.
- Public APIs and non-obvious boundary adaptations require KDoc. Cite Koog
  symbols by repository path and pi ports as
  `packages/<package>/src/<file>.ts` (with line numbers when stable/useful).

## Architecture

- **Koog runtime:** prompt execution, LLM clients, messages, model
  capabilities, tools, agent execution, and runtime features.
- **Pathfinder extensions:** secure API-key credential resolution, tree-backed
  session/history projection, and narrow Koog adapters.
- **Android app:** presentation, lifecycle, navigation, input, settings,
  persistence, secure credential storage, and platform launch/callback APIs.

UI follows single-activity Compose with MVVM/UDF: ViewModels expose immutable
state through `StateFlow`; composables stay state-hoisted apart from ephemeral
UI state and forward user intents. `PathfinderApplication` is the manual
composition root. Add a DI framework only if the graph clearly warrants it.

Do not duplicate Koog messages, models, stream events, or tool contracts in the
UI or persistence layer. Project them into UI/storage shapes only at explicit
boundaries. Session-tree entries may reference or encode Koog history, but the
conversion must be centralized and tested.

Provider and authentication-specific instructions are in
`app/src/main/kotlin/works/resolve/pathfinder/ai/AGENTS.md`.

## Security

Never log API keys, credential values, message text, tool
arguments/results, or model responses. Secret form values may live only in
ephemeral UI memory, and persisted credentials must remain inside the Android
Keystore-backed credential boundary.

## Commands

```bash
./gradlew test assembleDebug
```
