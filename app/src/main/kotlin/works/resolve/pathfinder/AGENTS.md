# Kotlin app-layer conventions

This file refines the repository instructions for code under
`works.resolve.pathfinder`.

## Classify the code before changing it

Every change should belong to one of these categories:

1. **Koog integration:** use Koog public types and behavior directly. Read the
   relevant source, tests, and `Module.md` under `~/Projects/koog/` first.
2. **Selected pi port:** currently only the tree-session semantics in
   `data/sessions`. Read current source and tests under `~/Projects/pi/`,
   preserve provenance, and adapt it to Koog at one boundary.
3. **Pathfinder/Android code:** follow current idiomatic Kotlin and official
   Android APIs without pretending the behavior comes from either upstream.

Do not introduce a fourth category for Pathfinder-owned runtime, provider, or
agent abstractions that Koog already supplies.

## Layout

- `agent/` — `ChatRuntime` (the permanent ViewModel⇄runtime seam) and
  `KoogChatRuntime`, its Koog implementation. ViewModels depend only on
  `ChatRuntime`.
- `ai/providers/` — `ProviderDescriptor`, the app's curated provider list
  (five providers; models enumerated from Koog `LLModelDefinitions`).
  Presentation metadata only; no protocol behavior lives here.
- `data/sessions/` — the pi-derived tree-session layer (`Conversation`,
  `Session*`) and `SessionCodec` (format 3; old formats fail fast).
- `data/credentials/` — Keystore-backed per-provider API-key storage.
- `data/settings/`, `ui/`, `logging/`, `telemetry/` — Pathfinder-owned.

There is no OAuth code; it was deleted with the old pi runtime and may be
rebuilt from git history later if a product path needs it.

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

## Session-tree port

Keep upstream exported names where that improves provenance and cite source in
KDoc as `packages/<package>/src/<file>.ts`, with line numbers when
stable/useful. Preserve data semantics, event ordering, cancellation, and
errors; document Koog/Android divergence at the narrowest adaptation point.
The conversion between stored tree entries and the Koog history projected for
the selected branch stays centralized and tested.

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

## Tests and provenance

The suite is deliberately tiny: tree semantics, the credential boundary,
`SessionCodec` v3, markdown parsers, provider descriptors, and runtime
lifecycle/cancellation against an injected fake client. Do not duplicate
Koog's own unit suite, add ViewModel choreography tests, or add per-provider
protocol tests. Selected pi ports keep parity tests tied to named upstream
behavior, with tests for every Koog/Android adaptation.
