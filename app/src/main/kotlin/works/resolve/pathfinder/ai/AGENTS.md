# Koog runtime and provider boundary

This file refines the repository instructions for code under
`works.resolve.pathfinder.ai`. Koog supplies LLM models, prompts, provider
clients, transport behavior, streaming, and agent-facing contracts. Pathfinder
adds provider presentation metadata and API-key credential resolution.

Read this file before changing providers, model selection, authentication,
credentials, transport, or their tests and tooling.

## Ownership boundary

Koog owns:

- prompt/message and model-capability types;
- provider clients and their request/response formats;
- streaming contracts, tool calls, usage, and provider errors;
- shared transport, retry, and prompt-execution behavior.

Pathfinder owns:

- choosing the Koog modules/providers exposed by the app;
- Android Keystore-backed credential persistence;
- provider configuration UI;
- app model/provider presentation and settings.

Do not build a Pathfinder-wide provider abstraction over Koog. Composition may
select/configure a Koog client, but runtime code should retain Koog's types and
contracts.

## Sources of truth

For runtime/provider behavior, inspect the current implementation and tests in
`~/Projects/koog/prompt/`, especially `prompt-llm`, `prompt-model`, and the
relevant `prompt-executor` client module. Also inspect `~/Projects/koog/http-client/`
when transport behavior matters.

When the upstream does not compose directly with an Android requirement,
document the mismatch and adapt at the credential/client construction boundary.
Do not silently alter upstream semantics.

## Runtime rules

- A usable provider path runs through credentials, a Koog client/executor,
  session history, the ViewModel, and boundary tests.
- Do not maintain Pathfinder message, model, event, payload, stream, retry,
  transport, or agent-loop abstractions where Koog supplies the contract.
- Reuse Koog serialization, transport, retries, and stream handling rather than
  placing a parallel implementation in front of Koog.
- Compatibility with superseded development credentials or settings is not
  required unless explicitly requested. Unknown formats fail clearly rather
  than triggering hidden conversion.

## Provider and model scope

Expose providers that work end to end on Android through a maintained Koog
client or an explicitly approved narrow adapter. Provider count is not a goal.
A provider is supported only when authentication, model selection, requests,
streaming, errors, cancellation, and app UX all work together.

Use Koog's `LLMProvider`, `LLModel`, and capabilities as the runtime model
surface. The app-owned provider surface (`ai/providers`) is presentation
metadata that enumerates Koog's own `LLModelDefinitions` singletons rather than
hand-copying models, so it cannot drift from the Koog runtime; it must not
create a parallel behavioral model registry.

Before adding a provider or capability, answer:

1. Is it supported by current Koog, or is the missing piece narrow enough to
   justify and maintain as an adapter?
2. Does it provide meaningful value in the Android client?
3. Is its authentication path complete for the credential type the app stores?
4. Can it use Koog runtime types without a parallel provider stack?
5. Can it be tested end to end at the relevant boundary?

If not, leave it out and record the reason rather than exposing partial support.

## Credentials

- Store credentials only through the Android Keystore-backed credential
  boundary. API keys are secrets.
- Map a resolved credential into the minimal input accepted by the Koog client;
  do not leak the app credential-store type through Koog-facing runtime code.

Never log or expose credentials in telemetry, `toString`, exceptions, request
diagnostics, or Compose state. Logs must also exclude prompts, message content,
tool data, and model output.

## Android and dependency decisions

Prefer Koog's supported modules and HTTP client integration over Pathfinder
transport code. Add only the provider modules required by the product surface;
do not depend on an all-providers bundle by default if narrower modules keep the
Android artifact and maintenance surface smaller.

Before adding a provider SDK or a new protocol/crypto dependency, verify Koog's
current implementation and request an explicit scope decision if the dependency
would bypass Koog or materially increase app size/security maintenance. Android
Keystore and lifecycle APIs remain behind Pathfinder platform adapters.

## Tests and provenance

- Test credential resolution and Koog client construction without real secrets.
- Use Koog test executors/utilities for agent and prompt behavior where
  available; do not maintain mocks for a parallel Pathfinder client API.
- Test cancellation, secret redaction, and malformed stored credentials.
- Integration tests requiring real provider keys must be opt-in and obtain them
  outside the repository.
