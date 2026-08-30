# Koog runtime and provider-auth boundary

This file refines the repository instructions for code under
`works.resolve.pathfinder.ai`. Koog supplies LLM models, prompts, provider
clients, transport behavior, streaming, and agent-facing contracts. Pathfinder
layers selected pi OAuth behavior on top.

Read this file before changing providers, model selection, authentication,
credentials, protocol adapters, transport, the generated catalog, or their
tests and tooling.

## Ownership boundary

Koog owns:

- prompt/message and model-capability types;
- provider clients and their request/response formats;
- streaming contracts, tool calls, usage, and provider errors;
- shared transport, retry, and prompt-execution behavior.

Pathfinder owns:

- choosing the Koog modules/providers exposed by the app;
- Android Keystore-backed credential persistence;
- provider sign-in UI and browser/callback integration;
- selected pi-derived OAuth flows and token refresh behavior;
- narrow adapters that supply resolved credentials to Koog clients;
- app model/provider presentation and settings.

Do not build a Pathfinder-wide provider abstraction over Koog. Composition may
select/configure a Koog client, but runtime code should retain Koog's types and
contracts.

## Sources of truth

For runtime/provider behavior, inspect the current implementation and tests in
`~/Projects/koog/prompt/`, especially `prompt-llm`, `prompt-model`, and the
relevant `prompt-executor` client module. Also inspect `~/Projects/koog/http-client/`
when transport behavior matters.

For an explicitly selected OAuth flow, inspect current pi source and tests in
`~/Projects/pi/packages/ai`. Pi is authoritative for authorization endpoints,
PKCE/device/manual-code mechanics, polling, token refresh, and provider-specific
credential fields. It is not authoritative for the prompt or provider-client
contract once the credential enters Koog.

When the upstreams do not compose directly, document the mismatch and adapt at
the credential/client construction boundary. Do not silently alter one
upstream's semantics to resemble the other.

## Runtime rules

- A usable provider path runs through credentials, a Koog client/executor,
  session history, the ViewModel, and boundary tests.
- Do not maintain Pathfinder message, model, event, payload, stream, retry,
  transport, or agent-loop abstractions where Koog supplies the contract.
- Reuse Koog serialization, transport, retries, and stream handling rather than
  placing a parallel implementation in front of Koog.
- Keep a pi-derived provider protocol adapter only when a selected OAuth
  product path cannot use an existing Koog client. Such an exception must be
  narrow, explicitly scoped, provenance-documented, and expressed through Koog
  prompt/runtime contracts.
- Compatibility with superseded development credentials or settings is not
  required unless explicitly requested. Unknown formats fail clearly rather
  than triggering hidden conversion.

## Provider and model scope

Expose providers that work end to end on Android through a maintained Koog
client or an explicitly approved narrow adapter. Provider count is not a goal.
A provider is supported only when authentication, model selection, requests,
streaming, errors, cancellation, and app UX all work together.

Use Koog's `LLMProvider`, `LLModel`, and capabilities as the runtime model
surface. A curated app list may add presentation metadata, but must not create a
parallel behavioral model registry.

App model selection is Koog-compatible and derives runtime identity and
capabilities from Koog. Any bundled catalog is app-owned presentation metadata,
must be generated rather than hand-edited, and cannot carry a parallel pi
protocol definition.

Before adding a provider or capability, answer:

1. Is it supported by current Koog, or is the missing piece narrow enough to
   justify and maintain as an adapter?
2. Does it provide meaningful value in the Android client?
3. Is its authentication path complete, including refresh/expiry behavior?
4. Can it use Koog runtime types without a parallel provider stack?
5. Can it be tested end to end at the relevant boundary?

If not, leave it out and record the reason rather than exposing partial support.

## OAuth and credentials

Selected pi OAuth flows should remain provider-specific. Share low-level,
behavior-neutral mechanics such as PKCE generation, loopback callback handling,
device-code polling primitives, and HTTP helpers; do not flatten distinct
provider semantics into a universal OAuth state machine.

- Open authorization in a Chrome Custom Tab/system browser, never a WebView.
- Keep the existing loopback callback approach only where the selected pi flow
  uses it and Android behavior is tested; retain the provider's manual/device
  fallback where applicable.
- Store credentials only through the Android Keystore-backed credential
  boundary. Access/refresh tokens are secrets.
- Refresh is performed according to the source provider flow before client
  construction or request execution. Persist rotated credentials atomically.
- Keep provider-specific opaque fields losslessly when refresh requires them.
- Map a resolved credential into the minimal input accepted by the Koog client;
  do not leak the app credential-store type through Koog-facing runtime code.
- If a Koog client assumes API-key authentication and an OAuth token requires
  different headers/endpoints, implement that difference at client
  configuration or a narrow client/HTTP adapter, with tests and provenance.

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
browser, Keystore, and lifecycle APIs remain behind Pathfinder platform
adapters.

## Tests and provenance

- Test credential resolution and Koog client construction without real secrets.
- Use Koog test executors/utilities for agent and prompt behavior where
  available; do not maintain mocks for a parallel Pathfinder client API.
- Keep pi parity tests for selected OAuth parsing, polling, refresh, expiry, and
  error behavior. Cite exact upstream symbols/files in test names or KDoc.
- Test cancellation, secret redaction, malformed stored credentials, and every
  Android/Koog divergence.
- Integration tests requiring real provider keys or tokens must be opt-in and
  obtain them outside the repository.
