# Kotlin runtime and selective pi-port conventions

This file refines the repository instructions for code under
`works.resolve.pathfinder`. Pathfinder integrates Koog rather than translating
pi into a parallel Kotlin runtime. Apply the pi translation rules below only to
selected features, principally provider OAuth and session-tree semantics.

## Classify the code before changing it

Every change should belong to one of these categories:

1. **Koog integration:** use Koog public types and behavior directly. Read the
   relevant source, tests, and `Module.md` under `~/Projects/koog/` first.
2. **Selected pi port:** translate only an approved feature. Read current source
   and tests under `~/Projects/pi/`, preserve provenance, and adapt it to Koog
   at one boundary.
3. **Pathfinder/Android code:** follow current idiomatic Kotlin and official
   Android APIs without pretending the behavior comes from either upstream.

Do not introduce a fourth category for Pathfinder-owned runtime, provider, or
agent abstractions that Koog already supplies.

## Koog integration

- Reuse Koog messages, prompts, `LLModel`/capabilities, prompt executors,
  provider clients, streaming contracts, tools, agent strategies, and feature
  hooks. Do not mirror them with Pathfinder data classes or interfaces.
- Prefer composition through Koog public APIs. A Pathfinder adapter should
  translate one product/platform concern—credentials, session history,
  Android transport or lifecycle—not wrap the entire Koog API.
- Keep conversions centralized and directional. Do not maintain parallel
  Pathfinder and Koog runtime models.
- Preserve Koog cancellation, exception, and flow semantics. Do not translate
  them into Pathfinder-owned stream-event or agent-loop contracts.
- If the current Koog API cannot support a requirement, verify that in source
  and tests. Prefer a small upstreamable Koog change or an installable feature
  over copied framework internals.
- Cite the relevant Koog source path in KDoc for subtle behavior or a necessary
  Android adaptation. Ordinary direct API use does not require provenance on
  every call.

## Selected pi ports

A selected pi module keeps upstream exported names when practical and cites its
source in KDoc as `packages/<package>/src/<file>.ts`, with line numbers when
stable/useful. Preserve data semantics, event ordering, cancellation, and
errors. Document Koog/Android divergence at the narrowest adaptation point.

### TypeScript shapes

- Translate discriminated unions as sealed classes/interfaces. Add a
  discriminator property only when it is serialized data; otherwise the Kotlin
  subtype is the tag.
- Translate string-literal unions as enums only when every case is a bare
  constant. Use a sealed type when cases carry data.
- Keep provider/runtime-opaque JSON as `JsonElement` until the boundary that
  consumes it. Do not parse and re-encode unknown fields unnecessarily.
- Use nullable function types for optional callbacks and interfaces for
  multi-method contracts.
- Prefer Kotlin data classes, nullability, exhaustive `when`, and coroutines
  over TypeScript-shaped compatibility abstractions.

These rules are for translated pi behavior. Do not translate a pi shape at all
when Koog already defines the runtime concept.

## Data and JSON

- Koog owns runtime wire formats and provider serialization. Do not route Koog
  clients through Pathfinder-owned payload builders or JSON accessors.
- Pi-ported OAuth formats and app persistence codecs may use the
  shared JSON DOM/accessors in `ai/utils/JsonDom.kt`; do not create private
  accessor-helper families in each file.
- Decode untrusted enum/string values with an exhaustive `when` and explicit
  unknown handling, never `valueOf`.
- Persisted Pathfinder codecs stay strict: reject malformed or unknown data,
  omit absent optional fields on encode, and reject old formats instead of
  adding migrations unless explicitly requested.
- Session persistence should have one tested conversion between stored tree
  entries and the Koog prompt/history projected for the selected branch.
- Model selection uses Koog model and capability types. App-owned catalog data
  may add presentation metadata but cannot define runtime protocol behavior.

## Errors, coroutines, and streams

- Preserve the owning API's contract: Koog behavior for Koog calls, pi behavior
  inside a selected port, and Android lifecycle behavior at the UI boundary.
- Always rethrow `CancellationException`. Never turn cancellation into an
  error event, persistence result, or user-visible failure.
- Run blocking I/O under an injected dispatcher. Use structured concurrency
  and `Flow` rather than callback bridges unless an Android/upstream API
  requires one.
- Use exceptions for transport/programmer failures and typed values for
  expected domain outcomes. Do not encode structured error information only in
  message text.
- Catch failures once at an owning boundary. Do not stack Pathfinder-specific
  stream-error conversion on top of Koog's own error handling.
- Injectable timing uses `kotlin.time.Clock` for wall time and
  `TimeSource.Monotonic` for elapsed time in Pathfinder-owned code.

## Authentication and redaction

- Credentials cross from the Android Keystore-backed store into a Koog client
  (or justified narrow adapter) only when needed. Do not place secrets in
  long-lived UI state or general settings models.
- OAuth provider responses may retain opaque extras, but secret-bearing values
  must be redacted from `toString`, exceptions, telemetry, and logs.
- Never log message content, prompts, tool arguments/results, or model output.
- Browser authorization uses Custom Tabs/system browser and a narrow callback
  boundary; never an embedded WebView.

## UI and sessions

- ViewModels project Koog runtime state and Pathfinder session state into
  immutable UI state. Composables do not call Koog clients or mutate session
  trees directly.
- Keep transient UI models small. Do not reproduce Koog's message/model/tool
  hierarchy for rendering; use centralized projectors into display blocks.
- Tree sessions are Pathfinder's selected pi-derived layer. Branch selection
  determines the ancestry projected into Koog history. Koog remains unaware of
  the persisted tree.
- UI state for concurrent conditions stays a data class with nullable fields;
  introduce a sealed result/error only when the UI must classify outcomes.

## Tests and provenance

- Koog integrations test configuration, conversion boundaries, lifecycle, and
  behavior Pathfinder adds; do not duplicate Koog's own unit suite.
- Selected pi ports include parity tests tied to named upstream behavior plus
  tests for every Koog/Android adaptation.
- Boundary tests use Koog-facing contracts and do not establish a parallel
  Pathfinder runtime abstraction.
