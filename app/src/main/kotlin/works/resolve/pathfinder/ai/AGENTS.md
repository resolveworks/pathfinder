# AI provider port scope

This file refines the repository-level instructions for
`works.resolve.pathfinder.ai`. It records why Pathfinder ports a focused set of
pi's provider adapters and authentication paths. These are product and
maintenance boundaries, not accidental missing work.

Read this file before changing AI providers, protocol adapters, authentication,
the generated model catalog, or their corresponding tests and tooling.

## The 20/80 rule

Pathfinder aims to carry the high-value provider surface that makes a useful
native phone client without reproducing every environment pi supports. Select
providers aggressively, then port the selected providers faithfully.

A smaller provider set is not permission to simplify pi's semantics inside that
set. Once a provider or protocol is included, preserve its model data, request
and response behavior, event ordering, authentication, errors, cancellation,
and tests as closely as Kotlin and Android allow.

Optimize for total maintenance cost rather than provider count. An adapter
belongs when it is useful on a phone, maps cleanly to current pi, and can be
implemented with a conventional Android boundary. Do not add a parallel domain
model or misleading partial adapter merely to make a difficult provider appear
supported.

## Implementation boundary

Port provider behavior directly to Kotlin using the app's existing transport,
serialization, coroutine, and Android/JDK infrastructure. Do not introduce
provider SDKs, AI frameworks, embedded language runtimes, or a universal OAuth
framework. Default to no new runtime dependency; request an explicit scope
decision when a dependency is needed to make a provider possible.

## Catalog and provider scope

The generated catalog is the static, app-supported provider surface. Inclusion
means that the provider's model protocols and authentication paths are intended
to work end to end; do not list a provider merely because pi knows its models.
Keep generation tied to current pi data and fail visibly when an exclusion or
provider definition becomes stale.

Retain static providers that can be ported faithfully using ordinary HTTPS
JSON/SSE transport and standard API-key, PKCE, manual-code, or device-code
authentication over narrow Android/JDK boundaries.

The following exclusions are deliberate:

- **Radius and `llama.cpp`** are dynamic providers. Supporting them would add a
  dynamic model store, discovery and refresh semantics, gateway configuration,
  and local inference-server management. That complexity adds little value to
  the focused phone client, so Pathfinder uses a bundled static catalog.
- **Amazon Bedrock** is a protocol and dependency outlier. Faithful support
  requires AWS binary EventStream handling, SigV4 signing, region and endpoint
  resolution, profiles and the default credential chain, IAM/session/web-
  identity cases, and AWS-specific errors. Pi delegates these to the AWS SDK.
  A bearer-token-only port would provide materially narrower semantics, while
  recreating full support without the SDK would add substantial
  security-sensitive maintenance.
- **Google Vertex AI** centers desktop/server Google Cloud behavior such as
  Application Default Credentials, service accounts, project and location
  resolution, and ambient configuration. Supporting only the Vertex Express
  API-key path would again expose a narrower provider than pi. Pathfinder keeps
  the ordinary Gemini API and omits Vertex to avoid that cloud credential
  subsystem.
- **Anthropic deferred tool loading and request metadata** are deliberate
  reductions at the anthropic-messages boundary (see the divergence KDoc in
  AnthropicMessagesPayload.kt): deferred tool loading (`splitDeferredTools`,
  `tool_reference`, `defer_loading`, `supportsToolReferences`) exists upstream
  for tool sets large enough to need on-demand loading, which a phone client
  with a focused tool set does not have, and it is entangled with the already
  excluded `StopReason "deferred"`/`DeferredHandle` shapes. `metadata.user_id`
  has no identity source on a single-user device. Server-side fallbacks, by
  contrast, are ported (`allowedFallbackModels` → `fallbacks` + the
  `server-side-fallback-2026-07-01` beta + fallback cost attribution).
- **Image-generation providers** are outside the selected conversational AI
  provider surface. They require a separate media-generation product surface
  rather than another chat protocol adapter.
- **pi's `anthropic-dangerous-direct-browser-access` header** is deliberately
  not sent (owner decision). pi includes it unconditionally in all three
  anthropic-messages createClient branches to relax CORS for browser clients
  (anthropic-messages.ts:907-965); Pathfinder's OkHttp transport is not a
  browser client, so the header is meaningless here and the rest of the wire
  shape follows pi.
- **Anthropic ambient auth-token paths** are deliberately reduced: pi maps
  ANTHROPIC_AUTH_TOKEN to `Authorization: Bearer` header auth and
  ANTHROPIC_OAUTH_TOKEN to an apiKey source (providers/anthropic.ts:24-36),
  but Android has no ambient env, and the port's credential boundary is the
  NoopAuthContext/keystore layer, so only ANTHROPIC_API_KEY is surfaced.
  Revisit if ambient-token auth becomes relevant.

Do not silently reintroduce an excluded provider through the catalog, a partial
auth path, or a third-party SDK. An exclusion can be revisited explicitly if
upstream or Android changes make a faithful implementation proportionate.

> Note: the Codex WebSocket transport, cached context, and zstd bullets above
> were reversed by an explicit owner decision and are now ported
> (OpenAiCodexResponsesApi.kt, OpenAICodexWebSocketSessions.kt,
> ai/transport/WebSocketTransport.kt, ai/utils/ZstdCompression.kt, plus the
> approved `zstd-jni` dependency). Their remaining divergences are documented
> at those boundaries: no AssistantMessage transport-failure diagnostics, no
> session-resources.ts lifecycle hook (the public close API plus idle TTL and
> max connection age own cleanup), and abort as coroutine cancellation.
> Pi's no-WebSocket-runtime branch (browsers and old Node falling back to SSE
> when no WebSocket constructor exists) is likewise not ported, by owner
> decision: the OkHttp WebSocket transport is required wiring, so the
> SSE fallback for a missing WebSocket runtime does not exist (real WebSocket
> failures still fall back to SSE exactly like pi).

## Adapter capability scope

Provider options such as grammar-constrained custom tools and deferred server
tools should be added only after the native core models the
corresponding pi concepts and data shapes. (Request hooks — onPayload /
onResponse — and samplingParams are now ported; see [SimpleStreamOptions]
and the per-adapter wiring sites for their pi provenance. Anthropic
server-side fallbacks — `allowedFallbackModels`, the `fallbacks` request
field, the `server-side-fallback-2026-07-01` beta, and fallback cost
attribution — are now ported at the anthropic-messages boundary.) Do not add
isolated wire fields that the runtime cannot represent correctly.

When a narrow option is omitted, document it at the adapter or model boundary
with the upstream symbol and the reason. Distinguish an intentional omission
from unfinished parity, and do not advertise the omitted capability through
the catalog or UI.

When concrete agent tools are eventually ported, mirror pi's coding-agent
`constrainedSampling` usage (read/write/edit/bash behind experimental strict
mode) so the ported constrained/strict tool sampling gets end-to-end
prefer/require coverage; the production tool registry is intentionally empty
until then.

## Reconsidering scope

Before adding a provider or expanding an adapter, answer all of the following:

1. Does it provide meaningful value in a native phone client?
2. Is there a current pi implementation and test surface to port rather than
   reinterpret?
3. Can Android-specific behavior stay at one narrow platform boundary?
4. Can it be maintained without a provider-specific framework, parallel
   runtime, or disproportionate security-sensitive code?
5. Can the supported path be end to end rather than a misleading partial
   implementation?

If not, leave it out and record the reason. If yes, read the current upstream
source and package README, port it with symbol-level provenance, document every
necessary divergence at its boundary, and test that divergence.
