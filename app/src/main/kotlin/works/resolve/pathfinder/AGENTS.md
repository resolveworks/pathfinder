# TS→Kotlin translation conventions

This file refines the repository-level instructions with the standard
idioms for translating TypeScript patterns from pi into Kotlin. Pi remains
the source of truth for behavior; these rules govern the Kotlin-side idioms
pi cannot dictate because TypeScript provides them implicitly — property
access, optional fields, unions, errors, timing, redaction. Where a rule
here would conflict with a faithful port of a specific pi file, provenance
wins: keep the local shape and document it in KDoc rather than generalizing.

## Sealed types and unions

- Port a discriminated union as a `sealed` class/interface. Add a
  discriminator property only when the tag is real data: a stored
  constructor property when it is a serialized wire field (credential
  `type`), or a computed `get()` when it merely mirrors a TS `type` field
  (`Content`, `Message`). Otherwise the Kotlin type is the tag — add
  nothing.
- Model TS string-literal unions as an `enum` when every case is a bare
  constant; use a sealed type as soon as any case carries data
  (`ToolChoice.Function`). Do not model a known union as a raw `String?`;
  if a field stays a passthrough string because pi types it loosely, record
  that divergence in KDoc.
- In new code, dispatch on the sealed subtype (`when (c) { is TextContent
  -> … }`), not on a discriminator enum followed by a cast.
- Canonical union translations — reuse these patterns with pi-citing KDoc:
  - `false | Config | undefined` → nullable sealed property plus a
    `Disabled` data object for the literal `false` (ConstrainedSamplingConfig).
  - `Record<K, string | null>` with three-state semantics →
    private-constructor wrapper with `isSpecified`/`forLevel` accessors
    (ThinkingLevelMap).
  - `string | T[]` → array-only property plus a companion factory
    (UserMessage.ofText).
- Provider- and runtime-opaque structured data stays `JsonElement` end to
  end (`Tool.parameters`, `ToolResultMessage.details`, `samplingParams`,
  OAuth extras). Parse only at the wire boundary for immediate
  re-serialization.

## JSON

- Hand-ported wire formats and persisted codecs use the JSON DOM with the
  shared accessor surface in `ai/utils/JsonDom.kt`. Never add a private
  accessor-helper family (`stringField`, `intOrNull(key)`, `obj(key)`, …);
  extend the shared surface instead. Use its strictness variants
  deliberately:
  - lenient reads (TS `String(x)` semantics) for provider stream fields;
  - strict `isString` reads (TS `typeof` semantics) for auth/protocol
    fields;
  - numeric reads use the kotlinx `intOrNull`/`longOrNull`/
    `doubleOrNull` semantics directly (quoted numerals accepted, floats
    rejected for int reads) — never hand-rolled coercions;
  - codecs use the throwing `require`-style variants.
- Use the shared `lenientJson` instance; do not construct per-file `Json
  {}` builders (encode-time `prettyPrint` is the only exception).
- `@Serializable` DTOs exist only for generated, stable, app-owned assets
  (the model catalog) and framework-mandated types (Navigation keys).
  Wire formats and persisted codecs stay hand-rolled DOM.
- Enum wire names use a `wire` constructor property. Decode wire strings
  from network input with an exhaustive `when` plus default — never
  `valueOf` on untrusted input. `valueOf` is acceptable only on catalog-
  or config-derived names where fail-fast is intended.
- Persisted codecs (SessionCodec, CredentialCodec) stay strict: throw the
  module exception on anything malformed or unknown, omit null optional
  fields on encode, and reject old formats rather than migrating.
- Conversions between parallel pi enums (ThinkingLevel ↔
  ModelThinkingLevel) go through the named shared mappers next to the
  enums in `ai/core/Types.kt`; never `valueOf(name)`. Provider-specific
  thinking levels (AnthropicEffort, Google) map through the model's
  thinkingLevelMap strings and stay local to their adapter.

## Errors and failure encoding

Follow Kotlin's canonical split: exceptions for programmer errors and
I/O, handled at one boundary; expected failures as values.

- The stream boundary converts every non-cancellation failure into a
  terminal `AssistantMessageEvent.Error` (Events.kt contract); the
  ViewModel boundary is the UI's single handler. Do not sprinkle try/catch
  at intermediate call sites.
- Expected failures are values: `null` for a single failure mode, a sealed
  result (`CompactionResult`) for multiple modes.
- Transport/IO exceptions extend `IOException` (ProviderHttpException,
  NetworkException, WebSocketCloseException). Domain exceptions extend
  `Exception` and carry structured data as typed fields (`status`,
  `code`) — never as text to be parsed back out of the message. An enum
  code is acceptable only where pi's error type has one (ModelsError).
- Never subclass `Error` or use Throwable-rooted exceptions for domain
  logic.
- `CancellationException` is always rethrown — never swallowed, wrapped in
  a failure value, or emitted as an Error event.
- `runCatching` only around non-suspending, expected-failure parse or
  cleanup; it catches `CancellationException` and is unsafe around suspend
  calls. No `kotlin.Result`.
- Missing provider credentials raise one typed exception
  (ProviderAuthException), not inline `IllegalStateException` at each
  adapter.
- Retry policy stays pi-faithful per adapter (ProviderRetry for transport,
  Retry for assistant calls). Shared retry-after/delay-cap logic is not
  duplicated per adapter; control-flow sentinels (DoneSentinel) are shared,
  not privately redeclared.

## Async

- Streams are `Flow<AssistantMessageEvent>` built with `flow {}`;
  failures terminate the flow per the Events.kt contract.
- Cooperative-cancellation checks use
  `currentCoroutineContext().ensureActive()`, not `Job?.isActive` probes.
- Blocking IO runs under an injected dispatcher (SessionStore pattern);
  never call blocking JDK code directly from domain code.
- Injectable timing uses `kotlin.time.Clock` (wall) and
  `TimeSource.Monotonic` (elapsed). No `System.currentTimeMillis()` in
  domain code and no mutable clock holders.
- Optional callbacks and request hooks are nullable (suspend) function
  types on options; interfaces only for multi-method contracts
  (AuthInteraction).

## Options and redaction

- Per-API options re-flatten pi's composition field-for-field; keep each
  class in sync with its upstream options type rather than inventing
  shared Kotlin-side bases.
- Options `toString()` redacts through the shared helper in `ai/utils`,
  never hand-written per class: secrets as `<redacted>`, maps (env,
  headers, samplingParams) as keys only, hooks as presence booleans.
- A redaction compiler plugin is deliberately rejected: it cannot express
  keys-only redaction and adds a compiler-plugin dependency.

## UI layer

- UI state for concurrent on-screen state stays a data class with nullable
  fields; introduce a sealed error type only when the UI must classify
  failures (retryable, auth), not merely display them.
- Project core types onto UI models via centralized `when`-based
  projectors (projectAuthPrompt, toChatBlocks); do not add a parallel UI
  enum without a projecting function.
- Only UI models touch kotlinx.serialization directly (Navigation keys);
  the domain core stays serialization-free apart from `JsonElement`.

## Provenance

- Ported symbols keep upstream names; KDoc cites pi as
  `packages/<pkg>/src/<file>.ts[:line]`, with line numbers whenever the
  upstream symbol is locatable. Document divergences at the narrowest
  boundary.

## Deliberately not standardized

Some variation mirrors pi rather than contradicting these rules — do not
"fix" it: Mistral's absent retry wrapper and its own error formatter,
Google's absent request hooks, per-API stop-reason mapping tables, and
provider-specific thinking levels mapping through thinkingLevelMap strings.
