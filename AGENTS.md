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
- The classic `packages/agent` Agent and agent loop.
- The classic `packages/coding-agent` `AgentSession` behavior used by the app:
  retry, compaction, conversation trees, JSONL persistence, and recovery, plus
  selected interaction semantics adapted into native Android UX. The built-in
  coding tools (read/bash/edit/write) with their Operations seam are ported
  scope; SSH transport is an app-layer adapter over that seam.
- The minimum telemetry contract required by those runtime pieces.

Do not port:

- The newer experimental stack rooted at upstream
  `packages/agent/src/harness/`: AgentHarness, its durable execution and effect
  machinery, multi-lane worker runtime, generic session backends, and related
  infrastructure. It is not a synchronization target; do not extend the
  existing classic session port toward it without an explicit scope decision.
- Extensions, hooks for third-party customization, Chord plugins/facets, pi
  packages, skills, prompt templates, themes, or resource discovery.
- The TUI, CLI, print/JSON/RPC/SDK modes, terminal keybindings, project trust,
  context-file discovery, or pi's local-file Operations adapter (the built-in
  tools run over remote SSH operations instead).
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

Comments and KDoc should contain only information the source cannot reasonably
convey. Preserve non-obvious rationale, invariants, cross-layer contracts, and
necessary divergences; do not narrate adjacent code or add routine upstream
provenance, file paths, or line citations. They are one author's commentary,
not specification: treat them as claims to confirm against the code and
upstream pi, never as evidence of behavior.

Keep provider-opaque data as `JsonElement`; reuse the shared JSON codecs and
accessors rather than introducing serializable mirror DTOs or private helper
families. Streaming uses `Flow`: preserve pi's event contract and always
propagate coroutine cancellation unchanged.

JS stdlib equivalents have one shared implementation, never a per-file one:
WHATWG query parsing and href normalization go through OkHttp's `HttpUrl`
(already a dependency, WHATWG-derived; verified byte-parity with
`URLSearchParams`/`href` for our inputs — including per-sequence malformed-
percent pass-through), form encoding through `URLEncoder` (byte-identical to
`URLSearchParams` serialization; OkHttp's `addQueryParameter` is NOT a form
encoder — space becomes `%20`), and `Number()`/`parseFloat` semantics through
the shared JS-number helpers (no JVM library implements them; hand-rolled is
the norm). Per-flow code keeps only what pi actually varies: scheme gates and
error channels.

Concurrency conventions: abort is coroutine cancellation — it never produces
a terminal Error event, and rethrows out of `prompt()`/`compact()` only after
the run's terminal state is committed. Where a `NonCancellable` tail must
still observe abort (pi reads `signal.aborted` there), a `@Volatile`
abort-requested flag mirrors the signal, because cancellation is structurally
invisible inside `NonCancellable`. Break out of a `Flow` collect with
truncation operators (`transformWhile`/`takeWhile`) when the break is a
per-element predicate, else a private sentinel exception caught immediately
outside the collect (kotlinx's own operators do this internally); sentinels
never cross a public API boundary or survive a generic catch. Timeouts use
`withTimeoutOrNull` — or a `TimeoutCancellationException` catch wrapping only
the `withTimeout` call — with real failures propagating from outside the
block, and plain `CancellationException` always rethrown. `NonCancellable` is
reserved for must-complete tails (terminal events, resource release) and used
alone in the context. Event delivery uses one awaited suspend sink plus a
suspending-emit `SharedFlow` for observers — never `tryEmit` where loss is
unacceptable. Serialization picks per call path: `Mutex` when the section
suspends, plain locks or `@Volatile` snapshots when it does not.

Time and deadlines: domain code injects `kotlin.time.Clock` (the
`Date.now()` analog); direct wall-time reads exist only in the helpers whose
job is to stamp wall time (uuidv7, synthetic tool-result stamps, diagnostics,
the bash output throttle), and each cites this rule. The shared OkHttp client
carries the 300 s inter-read idle cap (undici `bodyTimeout` analog) with
per-request header-phase deadlines (provider-SDK fetch-timeout analog);
OAuth HTTP uses whole-exchange call deadlines (the `AbortSignal.timeout`
analog).

The generated model catalog includes only providers supported end to end and
must not be hand-edited.

## Architecture

- Ported runtime code lives under `packages/`, in Gradle modules mirroring pi's
  `packages/ai`, `packages/agent`, `packages/coding-agent`, and
  `packages/telemetry`. Keep those modules platform-neutral.
- The Android application lives under `app/` and owns presentation, lifecycle,
  navigation, input, settings, credentials, platform adapters, and app-specific
  tools. It projects runtime state rather than duplicating it.
- Use single-activity Compose with MVVM/UDF, immutable `StateFlow` UI state, and
  state-hoisted composables.
- `PathfinderApplication` is the manual composition root. Add a DI framework
  only if the dependency graph clearly justifies it.
- Follow current official Android documentation and prefer stock Material 3
  behavior and components.

## Security

Never log API keys, credentials, message text, model responses, or tool
arguments and results. Secret form values remain ephemeral, secret-bearing
types must redact their string representations, and persisted credentials stay
behind the Android Keystore-backed boundary.

## Logging

`packages/` stays quiet like pi: failures surface through typed errors and
flows, and the app layer logs them. App code logs through slf4j only; debug
builds bind it to `android.util.Log` with a tag per class, logging info and
above; release builds log warn and above. Lifecycle transitions log at info, recoverable
degradation at warn, surfaced failures at error. Never log credentials,
message text, model responses, or tool arguments and results; log machine ids,
not machine addresses.

## Release

Bump `versionCode` and `versionName` in `app/build.gradle.kts` in a dedicated
`Release vX.Y.Z` commit on main, then tag that commit `vX.Y.Z` and publish a
GitHub release for it. Publishing triggers `.github/workflows/release.yml`,
which builds and signs the APK from the tag and uploads it to the release.
The tag must point at the bump commit: the workflow builds from the tag, so
an unbumped tag ships an APK whose versionCode does not advance and
Obtainium installs fail.

## Check

Pathfinder is in early alpha. Do not add regression tests unless explicitly
requested.

```bash
./gradlew spotlessCheck test assembleDebug
```
