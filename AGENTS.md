# pathfinder

A native Android AI client built on [Koog](https://github.com/JetBrains/koog).
Pathfinder uses Koog as its Kotlin LLM runtime and keeps the Android
application layer thin.

## Direction

- **Koog is the runtime foundation.** Use its prompt, message, model, client,
  streaming, and execution abstractions end to end. Depend on the narrow
  client modules the app needs rather than the `koog-agents` umbrella.
- **Extend Koog; do not fork it inside the app.** Prefer released Koog APIs and
  narrow Pathfinder adapters over copied framework internals or parallel
  runtime contracts. Consider upstreaming generally useful missing behavior.
- **Android owns platform behavior.** Follow current Android guidance and use
  Jetpack Compose, Material 3, and stock platform interactions.
- **Optimize for low maintenance.** Avoid speculative abstractions, bespoke UI
  primitives, compatibility shims, and dependencies whose value does not
  justify their upkeep.
- **Treat development data as disposable.** Support only current storage
  formats and reject old ones unless migration support is explicitly requested.
- **Track the platform.** Target the latest GrapheneOS release and current
  Android, Kotlin, AGP, Compose, and AndroidX versions. Resolve compatibility
  issues by moving forward rather than downgrading.

## Sources of truth

Use the source that owns the behavior being changed; do not work from
remembered APIs.

For runtime, prompt, model, provider, tool, streaming, or agent work, consult
the relevant source, tests, root `README.md`, and module `Module.md` in the
upstream checkout at `~/Projects/koog/`. The main areas are `prompt/`,
`agents/`, and `http-client/`. Treat that checkout as the API and behavioral
reference, not as code to copy into Pathfinder.

Koog owns runtime contracts, Android owns platform behavior, and Pathfinder
owns product policy and glue. Resolve conflicts with narrow adapters at those
boundaries.

## Project boundaries

- Runtime and provider code uses Koog types directly; do not introduce
  Pathfinder alternatives for messages, models, streaming, tools, or execution.
- ViewModels depend on `ChatRuntime`; Koog integration remains behind that
  boundary.
- Credentials remain in the Keystore-backed credential store and are supplied
  to provider clients only when needed.
- Branching and session persistence are Pathfinder concerns layered around Koog
  history; they must not alter Koog's runtime contracts.
- Provider catalog data is presentation metadata, not a parallel model or
  protocol surface.
- UI follows single-activity Compose with MVVM/UDF. ViewModels expose immutable
  state, composables keep only ephemeral UI state, and user actions flow back
  as intents. Add DI only when the object graph warrants it.

Use Koog terminology in Koog-backed code. Document public APIs and non-obvious
boundary adaptations where the rationale is not evident from the code.

## Tests

Keep tests focused on behavior Pathfinder owns, especially its persistence,
credential, parsing, catalog, and runtime boundaries. Do not duplicate Koog's
provider protocol or framework coverage, and prefer boundary tests over UI
choreography tests.

## Security

Never log API keys, tokens, credential values, message text, tool
arguments/results, or model responses. Secret form values may live only in
ephemeral UI memory, and persisted credentials must remain inside the Android
Keystore-backed credential boundary.

## Commands

```bash
./gradlew test assembleDebug
```
