# pathfinder

A native Android AI client built on [Koog](https://github.com/JetBrains/koog).
Pathfinder uses Koog as its Kotlin LLM runtime and keeps the Android
application layer thin.

## Direction

- **Koog is the runtime foundation.** Use Koog's prompt, message, model,
  provider-client, streaming, and execution abstractions end to end.
  Pathfinder consumes the narrow per-client artifacts
  (`ai.koog:prompt-executor-{anthropic,openai,openrouter}-client`,
  `prompt-executor-{google,mistralai}-client`), `ai.koog:http-client-ktor`
  over Ktor/OkHttp — not the `koog-agents` umbrella.
- **Extend Koog; do not fork it inside the app.** Prefer Koog modules and
  extension points. Fill product-specific gaps with narrow adapters. If a
  generally useful capability is missing, consider an upstream Koog
  contribution before copying framework internals into Pathfinder.
- **Android is the application source of truth.** Use current platform
  guidance, Jetpack Compose, Material 3, and stock Android components and
  interactions.
- **Optimize for low maintenance.** Avoid parallel domain models, speculative
  abstractions, bespoke UI primitives, compatibility shims, and dependencies
  whose value does not justify their upkeep.
- **Treat development data as disposable.** Implement only the app's current
  storage formats. Reject old formats rather than adding migrations unless
  explicitly requested.
- **Track the platform.** Target the latest GrapheneOS release and current
  Android, Kotlin, AGP, Compose, and AndroidX versions. Move forward to
  resolve compatibility issues rather than downgrading.

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
- `agents/` for agent execution and features;
- `http-client/` for transport integration.

Use released Koog artifacts and public APIs where practical. The local checkout
is the behavioral and API reference, not code to copy wholesale. When an
Android constraint requires a divergence, keep it in a narrow platform adapter
and test it.

### Precedence

Koog owns runtime contracts; Android owns platform behavior; Pathfinder owns
product policy and glue. Resolve conflicts at an adapter boundary instead of
modifying one layer to impersonate another.

## Implementation boundaries

- Runtime and provider code uses Koog types end to end; Pathfinder does not
  maintain alternative message, model, event, streaming, tool, or execution
  contracts.
- `runtime/ChatRuntime.kt` is the permanent seam between ViewModels and the
  runtime. `runtime/KoogChatRuntime.kt` is its Koog implementation: per-prompt
  credential read, a Koog `Prompt` built from the active branch,
  `executeStreaming` frames folded into state, abort that commits rendered
  partials, fixed user-safe error strings, and a shared Ktor/OkHttp engine.
  ViewModels depend only on `ChatRuntime`.
- Authentication is a per-provider API key stored in the Keystore-backed
  `data/credentials` store and supplied to Koog clients at prompt time.
- Ten providers are declared in `runtime/ProviderDescriptor.kt`; models
  are enumerated from Koog `LLModelDefinitions` where Koog ships a client
  (DeepSeek, DashScope included), and hand-declared as Koog `LLModel`s from pi's
  catalogs for coding-plan endpoints without a Koog client module (Z.AI,
  Kimi — executed by Koog's stock OpenAI/Anthropic clients against their
  coding base URLs, `runtime/CodingPlanModels.kt`). App catalog data is
  presentation metadata only, never a parallel protocol surface.
- Tree-session behavior is a Pathfinder session layer around Koog history;
  branching semantics do not leak into Koog's runtime. Koog `Message` is
  persisted via the `SessionCodec` format (version 3); old formats fail fast.
- Compatibility bridges, dual runtime stacks, and migration machinery are not
  part of the architecture.

## Naming and style

- Koog-backed code uses Koog concepts and types directly; do not wrap them in
  Pathfinder aliases.
- Pathfinder-owned and Android code follows current idiomatic Kotlin: data
  classes, sealed types, nullability, coroutines, and standard library APIs.
- Public APIs and non-obvious boundary adaptations require KDoc. Cite Koog
  symbols by repository path.

## Architecture

- **Koog runtime:** prompt execution, LLM clients, messages, model
  capabilities, streaming, and transport.
- **Pathfinder extensions:** tree-backed session/history projection
  (`data/sessions`), Keystore-backed API-key credentials (`data/credentials`),
  and the `runtime` package: provider descriptors and the `ChatRuntime`
  seam.
- **Android app:** presentation, lifecycle, navigation, input, settings,
  persistence, and platform APIs (`ui`, `MainActivity`, `PathfinderApplication`
  as the manual composition root).

UI follows single-activity Compose with MVVM/UDF: ViewModels expose immutable
state through `StateFlow`; composables stay state-hoisted apart from ephemeral
UI state and forward user intents. Add a DI framework only if the graph
clearly warrants it.

Do not duplicate Koog messages, models, stream events, or tool contracts in
the UI or persistence layer. Project them into UI/storage shapes only at
explicit boundaries. The conversion between stored session-tree entries and
Koog history is centralized and tested.

## Tests

The suite is intentionally tiny. Koog's own suite owns runtime behavior;
Pathfinder tests only what it adds:

- tree-session semantics;
- the credential boundary;
- `SessionCodec` format 3 round-trips and old-format rejection;
- markdown parsers and provider descriptors;
- runtime lifecycle/cancellation with an injected fake client.

No ViewModel choreography tests, no per-provider protocol tests.

## Security

Never log API keys, tokens, credential values, message text, tool
arguments/results, or model responses. Secret form values may live only in
ephemeral UI memory, and persisted credentials must remain inside the Android
Keystore-backed credential boundary.

## Commands

```bash
./gradlew test assembleDebug
```
