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
- **Image-generation providers** are outside the selected conversational AI
  provider surface. They require a separate media-generation product surface
  rather than another chat protocol adapter.

Do not silently reintroduce an excluded provider through the catalog, a partial
auth path, or a third-party SDK. An exclusion can be revisited explicitly if
upstream or Android changes make a faithful implementation proportionate.

## Adapter capability scope

Provider options such as grammar-constrained custom tools, deferred server
tools and fallbacks, and request hooks should be added only after the native
core models the corresponding pi concepts and data shapes. Do not add isolated
wire fields that the runtime cannot represent correctly.

When a narrow option is omitted, document it at the adapter or model boundary
with the upstream symbol and the reason. Distinguish an intentional omission
from unfinished parity, and do not advertise the omitted capability through
the catalog or UI.

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
