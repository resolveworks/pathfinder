# Kotlin app-layer conventions

This file refines the repository instructions for code under
`works.resolve.pathfinder`.

## Classify the code before changing it

Every change should belong to one of these categories:

1. **Koog integration:** use Koog public types and behavior directly. Read the
   relevant source, tests, and `Module.md` under `~/Projects/koog/` first.
2. **Pathfinder/Android code:** follow current idiomatic Kotlin and official
   Android APIs without pretending the behavior comes from Koog.

Do not introduce a third category for Pathfinder-owned runtime, provider, or
agent abstractions that Koog already supplies.

## Layout

- `runtime/` — the model-facing layer over Koog. `ChatRuntime` is the
  permanent ViewModel⇄runtime seam and `KoogChatRuntime` its Koog
  implementation; ViewModels depend only on `ChatRuntime`. Also holds
  `ProviderDescriptor`, the app's curated provider list (nine providers;
  models enumerated from Koog `LLModelDefinitions` where Koog ships a
  client, or hand-declared as Koog `LLModel`s for coding-plan endpoints in
  `CodingPlanModels` — presentation metadata only), and the ChatGPT Codex
  client/OAuth glue (`CodexLLMClients`, `CodexOAuthClient`, and
  `CodexLoopbackServer`, the loopback listener that catches the browser
  flow's redirect).
- `data/sessions/` — the tree-session layer (`Conversation`,
  `Session*`) and `SessionCodec` (format 3; old formats fail fast).
- `data/credentials/` — Keystore-backed per-provider API-key storage.
- `data/settings/`, `ui/` — Pathfinder-owned.

## Koog integration

- Reuse Koog messages, prompts, `LLModel`/capabilities, provider clients, and
  streaming contracts. Do not mirror them with Pathfinder data classes or
  interfaces.
- Prefer composition through Koog public APIs. A Pathfinder adapter should
  translate one product/platform concern — credentials, session history,
  transport — not wrap the entire Koog API.
- Preserve Koog cancellation, exception, and flow semantics. `KoogChatRuntime`
  folds `executeStreaming` frames into state and commits rendered partials on
  abort; do not translate Koog events into a Pathfinder-owned stream contract.
- If the current Koog API cannot support a requirement, verify that in source
  and tests. Prefer a small upstreamable Koog change over copied framework
  internals.
- Cite the relevant Koog source path in KDoc for subtle behavior or a
  necessary Android adaptation.

## Data and JSON

- Koog owns runtime wire formats and provider serialization. Do not route Koog
  clients through Pathfinder-owned payload builders or JSON accessors.
- App persistence codecs may use the shared JSON DOM/accessors; do not create
  private accessor-helper families in each file.
- Decode untrusted enum/string values with an exhaustive `when` and explicit
  unknown handling, never `valueOf`.
- Persisted codecs stay strict: reject malformed or unknown data, omit absent
  optional fields on encode, and reject old formats instead of adding
  migrations unless explicitly requested.

## Errors, coroutines, and streams

- Always rethrow `CancellationException`. Never turn cancellation into an
  error event, persistence result, or user-visible failure.
- Run blocking I/O under an injected dispatcher. Use structured concurrency
  and `Flow` rather than callback bridges unless an Android/upstream API
  requires one.
- Use exceptions for transport/programmer failures and typed values for
  expected domain outcomes. `KoogChatRuntime` surfaces fixed user-safe error
  strings; do not leak provider/transport detail into UI state.
- Injectable timing uses `kotlin.time.Clock` for wall time and
  `TimeSource.Monotonic` for elapsed time in Pathfinder-owned code.

## Credentials

- API keys cross from the Keystore-backed store into Koog client construction
  only when a prompt runs (`KoogChatRuntime` reads credentials per prompt).
  Do not place secrets in long-lived UI state or general settings models.
- Never log message content, prompts, tool arguments/results, model output, or
  any credential value.

## UI and sessions

- ViewModels project `ChatRuntime` state and session state into immutable UI
  state. Composables do not call Koog clients or mutate session trees
  directly.
- Keep transient UI models small. Do not reproduce Koog's message/model
  hierarchy for rendering; use centralized projectors into display blocks.
- Branch selection determines the ancestry projected into Koog history. Koog
  remains unaware of the persisted tree.

## Tests

The suite is deliberately tiny: tree semantics, the credential boundary,
`SessionCodec` v3, markdown parsers, provider descriptors, and runtime
lifecycle/cancellation against an injected fake client. Do not duplicate
Koog's own unit suite, add ViewModel choreography tests, or add per-provider
protocol tests.
