# pi provider-layer (ai/) parity audit — Pathfinder vs upstream

Read-only audit, 2026-09. Compares:

- **Pathfinder**: `app/src/main/kotlin/works/resolve/pathfinder/ai/`
  (`api/`, `core/`, `models/`, `providers/`, `transport/`, `utils/`, `auth/`).
- **pi** (`~/Projects/pi`, behavioral source of truth): `packages/ai/src/`
  (`api/`, `auth/`, `utils/`, `types.ts`, `models.ts`, `models.generated.ts`).

**Ledger:** P1-1, P1-2, P1-3, P2-1 (Kimi), and P2-2 all landed, and the
catalog was regenerated, in `agent/ai-parity-fixes` (650cefd, merged 06da849).
P2-4 remains monitor-only. Status lines below.

The provider layer was last parity-audited 2026-08-27→29 (fix batches:
"Fix five P2 Codex/Responses/Azure parity gaps", "Fix four P2 parity gaps in
the OpenAI Chat Completions adapter", `agent/p2-codex-azure-fixes`,
`agent/p2-completions-fixes`, `agent/p2-mistral-core-consolidation`,
`agent/completions-reasoning-details`, `agent/cost-1h-cache-write`), followed
by cleanup sweeps (Clock injection, JsonDom migration, resp* shim removal).
Upstream `packages/ai` has moved only slightly since; the drift section below
covers each change.

Scope decisions (which providers/protocols are ported at all) are recorded in
`app/src/main/kotlin/works/resolve/pathfinder/ai/AGENTS.md` and were treated as
product boundaries, not gaps.

---

## P0 — none found

No P0 gaps. Every ported adapter's core request/response/event/cost semantics
matched upstream at the level checked. The P1s below are real behavioral bugs
or missing flags on specific provider paths, not structural distortions.

---

## P1 — behavioral divergences on paths Pathfinder ships

### P1-1. Mistral indexed tool-call chunks are not merged (upstream fix not ported)

**Status:** landed — 650cefd.

- **pi**: `packages/ai/src/api/mistral-conversations.ts:696`
  (`consumeChatStream`) — commit `6c87d9a02` (2026-08-28, "fix(ai): merge
  indexed Mistral tool call chunks", issue #8387) changed the streaming
  tool-call block key from `` `${callId}:${toolCall.index || 0}` `` to
  `toolCall.index ?? callId`, so indexed fragments merge regardless of whether
  later chunks carry the same (or any) `id`.
- **Pathfinder**: `api/MistralConversationsApi.kt:615` still uses the *old*
  key `` val key = "$callId:$index" ``. When a gateway (the #8387 case) sends
  the id only on the first indexed chunk, later chunks derive a synthetic
  `toolcall:<index>` id, the composite key differs, and one tool call splits
  into two — the second with a bogus id that fails replay.
- **Faithful port**: key the `toolBlocksByKey` map by `index ?: callId` and,
  like pi, only set id/name at block creation (existing blocks keep their
  first id; a later chunk's derived id never overwrites it).
- **Size**: S (~15 lines + tests).

### P1-2. `supportsMaxOutputTokens` compat flag missing (upstream drift, 2026-09-01)

**Status:** landed — 650cefd.

- **pi**: commit `b8b873b98` (#8941) adds
  `OpenAIResponsesCompat.supportsMaxOutputTokens` (default `true`;
  `types.ts:642`, `api/openai-responses.ts:75`) and gates
  `params.max_output_tokens` on it in `buildParams`
  (`api/openai-responses.ts:298-300`). Some Codex-protocol gateways reject
  `max_output_tokens` with 400.
- **Pathfinder**: `core/Types.kt` (`OpenAiResponsesCompat`),
  `OpenAiResponsesShared.ResolvedResponsesCompat`/`getCompat`, and
  `OpenAiResponsesApi.buildParams` all lack the flag; `maxTokens` always sends
  `max_output_tokens`. Additionally `providers/ProviderCatalog.kt`
  `CompatDto.toResponsesDomain` whitelists compat keys, so a future
  regenerated catalog carrying `supportsMaxOutputTokens: false` would be
  silently dropped (see P2-2). Azure/Codex `buildParams` are unaffected
  upstream (flag defaults true there; codex never sends `max_output_tokens`).
- **Faithful port**: add the nullable flag to the responses compat types,
  resolve it with `?? true` in `getCompat`, and gate the
  `max_output_tokens` put in `OpenAiResponsesApi.buildParams`; extend the
  catalog DTO mapping.
- **Size**: S (~20 lines + tests).

### P1-3. `requiresReasoningContentOnAssistantMessages` not ported (OpenAI Completions)

**Status:** landed — 650cefd.

- **pi**: `api/openai-completions.ts:1344-1349` — for compat models
  (`requiresReasoningContentOnAssistantMessages`, DeepSeek-style endpoints)
  with `model.reasoning`, replayed assistant messages always carry
  `reasoning_content: ""` when no reasoning field was set; some providers
  reject assistant messages without it.
- **Pathfinder**: `OpenAiCompletionsPayload.convertAssistantMessage` never
  emits `reasoning_content` on empty. The catalog ships **34 models** with
  `requiresReasoningContentOnAssistantMessages: true` (deepseek, moonshotai,
  moonshotai-cn, xiaomi*, cloudflare deepseek endpoints), so the flag is
  already parse-relevant but inert.
- **Faithful port**: after content/toolCalls assembly, emit
  `assistant["reasoning_content"] = ""` when the compat flag and
  `model.reasoning` hold and no reasoning field was set.
- **Size**: S (~10 lines + tests).

---

## P2 — narrower gaps and systemic risks

### P2-1. Kimi deferred tool loading (`deferredToolsMode: "kimi"`) not ported

**Status:** landed — 650cefd (deferredToolsMode "kimi").

- **pi**: `api/openai-completions.ts` — `getDeferredToolNames` (from
  `ToolResultMessage.addedToolNames`), filtering active vs deferred tools in
  `buildParams` (~:860-870), the Kimi `system` message with a bare `tools`
  array on tool results (`convertMessages` ~:1395-1415).
- **Pathfinder**: the completions payload ignores `deferredToolsMode` and
  `addedToolNames` entirely (only the Responses family ports deferred tools).
  Catalog ships 4 kimi-mode models (`fireworks` ×2, `moonshotai`,
  `moonshotai-cn`). Kimi's deferred tool contract is a distinct mechanism from
  the deliberately-excluded Anthropic `tool_reference` deferral; these models
  currently send all tools eagerly (works, but is not pi's wire shape once a
  session has loaded tools).
- **Faithful port**: port the three pieces above into
  `OpenAiCompletionsPayload` (+ `addedToolNames` wiring already exists in core).
- **Size**: M (~80 lines + tests).

### P2-2. Catalog compat parsing silently drops unknown compat keys

**Status:** landed — 650cefd (CompatDto rejects unknown compat keys).

- **pi**: model `compat` is an open surface that grows flags
  (`supportsMaxOutputTokens` being the latest example).
- **Pathfinder**: `providers/ProviderCatalog.kt` `CompatDto` /
  `toResponsesDomain` / `toAnthropicDomain` whitelist fields, and the catalog
  JSON is parsed leniently, so any new upstream compat key disappears without
  a signal. `ai/AGENTS.md` promises generation "fails visibly when an
  exclusion or provider definition becomes stale"; the decode side does not.
- **Faithful port**: either decode `compat` verbatim (raw map consulted by the
  adapters' `getCompat`) or add a unit test that regenerates the catalog in CI
  and asserts every emitted compat key is consumed by `CompatDto`.
- **Size**: S–M.

### P2-3. Bundled model catalog is stale vs current pi (data, not code)

**Status:** landed — catalog regenerated in 650cefd.

Regenerating `app/src/main/assets/models-catalog.json` from the current
checkout (`node tools/generate-model-catalog.mjs`) and diffing against the
bundled asset shows normal models.dev churn (checked 2026-09-01, pi @
`b8b873b98`):

- Removed upstream: `anthropic/claude-fable-5.1` (also via opencode/openrouter/
  vercel), `zai-org/GLM-5.3` (baseten/huggingface/together/cloudflare
  variants), `fireworks/glm-5p3(-flash)`, `groq/qwen3.8-27b`,
  `opencode/ling-3.0-flash-fin-free`, `opencode-go/hy4-preview`, several
  `:batch` openrouter entries, vercel `qwen3.8-flash-next`,
  `xiaomi/mimo-v2.5-pro-ultraspeed`, and others.
- Added upstream: `google/gemini-robotics-er-1.6-preview`,
  `nvidia/nemotron-3-nano-30b-a3b`, `fireworks/deepseek-v4-flash`,
  `opencode/hy3-free`, `openrouter/anthropic/claude-opus-4.7-fast` /
  `-4.8-fast` / `opus-5-fast`, `arcee-ai/virtuoso-large`,
  `kwaipilot/kat-coder-air-v2.5`, `mistralai/codestral-2508:batch`, and others.

No per-model compat/policy changes beyond the id churn (all other fields
byte-identical). Upstream commit `5ce4afbd9` ("refresh generated image model
catalog") is in the same category and does not touch Pathfinder's text-model
asset beyond this churn. Fix: regenerate the asset (one command) and review.
**Size**: S.

### P2-4. Dormant completions compat fields (monitor, no current catalog use)

**Status:** unchanged — monitor.

`thinkingTokenBudgetField`/`supportsThinkingTokenBudget`,
`chatTemplateKwargs` (chat-template / qwen-chat-template thinking formats),
`openRouterRouting`, `vercelGatewayRouting`, `requiresAssistantAfterToolResult`,
`requiresToolResultName`, `supportsFinishReason=false`, and grammar-tool
streaming in **completions** (grammar tools exist only on Responses-family
models today, where they *are* ported) are not implemented in
`OpenAiCompletionsPayload`. None of these appear in the current bundled
catalog, so behavior is currently identical; they become live gaps the moment
pi's catalog emits them (and, per P2-2, silently). No action now beyond P2-2.

---

## Already aligned (checked, found faithful)

- **Anthropic Messages** (`AnthropicMessagesApi/Payload.kt` vs
  `anthropic-messages.ts`): hand-rolled SSE decoder semantics (event-name
  filter set, error events, message_start/message_stop bookkeeping,
  ended-before-stop and no-stop-reason errors); OAuth/Copilot/API-key client
  branches incl. beta negotiation (fine-grained-tool-streaming gating,
  interleaved-thinking skip for adaptive models, server-side-fallback beta +
  `fallbacks` param + fallback-cost `usageModel` on message_start); Claude
  Code tool-name round-trip and identity system prompt; cache-control
  retention/TTL resolution incl. `PI_CACHE_RETENTION`, session-affinity
  header, last-user-message/tool/system marker placement; thinking config
  (adaptive effort + `output_config`, budget + display), temperature gating,
  `allowEmptySignature` replay, redacted thinking replay; usage seeding from
  message_start with `cache_creation.ephemeral_1h_input_tokens`,
  message_delta null-preserving updates, `output_tokens_details.thinking_tokens`,
  computed totals; stop-reason map incl. `pause_turn`/`sensitive`; simple
  options path (`adjustMaxTokensForThinking`, `clampMaxTokensToContext`,
  MIN_ANSWER_TOKENS, DEFAULT_THINKING_BUDGETS, `clampReasoning`).
  Deliberate exclusions (deferred tools/`tool_reference`, metadata.user_id,
  browser-access header, JSON-repair parsing) are documented in KDoc and
  still accurately reflect upstream.
- **OpenAI Completions** (`OpenAiCompletionsApi/Payload/ReasoningDetails.kt` vs
  `openai-completions.ts`): chunk loop (responseId/responseModel first-non-empty
  semantics, usage from chunk and `choice.usage` fallback, first-choice only),
  text/`reasoning_content|reasoning|reasoning_text` thinking with opencode-go
  remap, fragmented tool calls keyed by index/id with `parseStreamingJson`
  semantics replaced by raw-string accumulation (documented divergence),
  `reasoning_details` streaming/replay incl. the Aug-26
  serialize-signature-once fix (#8671, `7aab6c26e` — ported) and legacy
  encrypted `thoughtSignature` fallback; finish-reason fallbacks and
  `supportsFinishReason` errors; `parseChunkUsage` cache-read/write semantics
  (incl. `prompt_cache_hit_tokens`, Kimi `cached_tokens`,
  `cache_write_tokens`, no write-subtraction); anthropic-style
  `cache_control` placement (first instruction message, last tool, last
  conversation text, empty-string unmarkable); `prompt_cache_key`/`prompt_cache_retention`
  gating; zai/qwen/deepseek/openrouter/together/ant-ling/baseten/openai
  thinking formats incl. explicit-null-OFF handling and `chat_template_args`
  `$var` resolution; strict-tool conversion; OpenRouter `metadata.raw` error
  append; tool-call id normalization (pipe-split, 40-char cap + shortHash).
- **OpenAI Responses family** (`OpenAiResponsesApi`, `OpenAiResponsesShared.kt`
  vs `openai-responses.ts`, `openai-responses-shared.ts`, plus Azure):
  buildParams (prompt-cache key/retention/explicit-mode, store:false, min-16
  output tokens, deferred-tools additional-tools/tool-search modes with
  `tool_search_call`/`tool_search_output` items and `fc_`/`msg_pi_`/hash id
  normalization, reasoning effort/summary + encrypted-content include, xAI
  include, copilot off-effort skip, tool_choice passthrough, samplingParams
  last); message replay (text signatures `TextSignatureV1`, reasoning-item
  signatures, namespace replay gating, custom_tool_call round-trip, image
  input parts, tool-result output shapes); stream machine (slots by
  output_index, summary-part `\n\n`, refusal deltas, custom-tool-call input
  JSON buffer, output_item.done without added, Azure `encrypted_content`
  backfill (#6409), completed/incomplete usage + tier pricing, phase
  `final_answer` stop, failed/error events, terminal-event requirement);
  Azure URL normalization/deployment map/api-version headers. Only the new
  `supportsMaxOutputTokens` flag is missing (P1-2).
- **OpenAI Codex** (`OpenAiCodexResponsesApi.kt`, `OpenAICodexWebSocketSessions.kt`
  vs `openai-codex-responses.ts`): SSE+WebSocket transports with
  cached-context continuation (delta input computation, `previous_response_id`,
  continuation invalidation), pooled per-session/account sockets with idle TTL
  and age limits, SSE-sticky fallback on transport failure,
  connection-limit/previous-not-found retry-once, debug stats, request-body
  zstd compression, terminal-rate-limit and retry-after handling, friendly
  usage-limit messages, `end_turn` capture, codex status whitelist
  normalization, `originator` (documented owner-approved `pathfinder`
  divergence), WebSocket header quirks verified against upstream.
- **Google Generative AI** (`GoogleGenerativeAiApi/GoogleRequest/GoogleShared/
  GoogleStreamEngine.kt` vs `google-generative-ai.ts`/`google-shared.ts`):
  REST wire shape over the SDK, thinking levels vs 2.5 budgets
  (Gemini-3/Gemma-4 classes, disabled-thinking configs), thought signatures,
  strict tool sampling, function-calling mode resolution, usage mapping incl.
  thoughts tokens, tool-call id synthesis, stop-reason map.
- **Mistral** (`MistralConversationsApi/Payload.kt` vs
  `mistral-conversations.ts`): wire payload remapping, 9-char tool-call id
  derivation with collision retry, `x-affinity` prompt caching, thinking/text
  chunk streaming, error formatting with body truncation, cached-token usage
  extraction, tool-result text construction, reasoning-effort vs
  prompt-mode model gates, indexed chunk merging *except* the Aug-28 key fix
  (P1-1).
- **Shared plumbing**: `TransformMessages.kt` (image downgrade with
  placeholder dedupe, same-model thinking/signature retention, redacted-thinking
  drop, orphaned tool-call synthetic results, error/aborted turn skipping) is
  a faithful single port used by every adapter; `GithubCopilotHeaders.kt`
  (initiator inference, vision header, merge order); `ConstrainedSampling.kt`
  (strict JSON-schema rewriting incl. unsupported-key errors and
  nullable-optional wrapping, grammar tools + input JSON buffer, strict
  sampling resolution); `simple-options` equivalents; `Retry.kt` /
  `ProviderRetry.kt` (SDK-mirroring retryable classification, header delays,
  delay caps; codex-local parser kept separate exactly as upstream);
  `ErrorBody.kt` / `Overflow.kt` / `TokenEstimate.kt` (4 chars/token, 4800
  image chars) / `Uuid.kt`; WebSocket transports (OkHttp) with documented
  divergences; `core/Types.kt`, `Events.kt`, `Model.kt` (usage incl.
  `cacheWrite1h` and 2× 1h-write cost math, tiers, thinking-level clamping,
  `modelsAreEqual`).
- **Models auth resolution** (`models/Models.kt`, `providers/ProviderCatalog.kt`
  vs `models.ts`/provider files): calculateCost tiers + 1h cache-write math,
  getSupportedThinkingLevels/clampThinkingLevel, per-credential baseUrl
  (Copilot proxy), Cloudflare bearer-header auth shape, API-key/OAuth gating.
- **Auth/OAuth** (`auth/`, `auth/oauth/` vs `packages/ai/src/auth/`): PKCE
  loopback flows for Anthropic/Codex with manual-code raced fallback, GitHub
  Copilot device-code, xAI, OpenRouter, KimiCoding (SLM) — client ids, scopes,
  token/authorize URLs, refresh handling, and device-code poller shapes match
  upstream values (verified constants for anthropic, codex, copilot, xai,
  kimi-coding); loopback callback race handling and foreground gating are
  documented Android adaptations. Upstream `auth/` has not changed since the
  last audit (no commits after 2026-08-29).

---

## pi drift since 2026-08-29 (`packages/ai`)

`git -C ~/Projects/pi log --since=2026-08-29 --oneline -- packages/ai`:

| Commit | Change | Affects Pathfinder? |
| --- | --- | --- |
| `b8b873b98` (2026-09-01) | `supportsMaxOutputTokens` compat flag for openai-responses (#8941) | **Yes — P1-2.** Flag and buildParams gate missing. |
| `a63fb12c1` (2026-09-01) | NO_PROXY matches subdomains/root domains (`utils/node-http-proxy.ts`, #8737) | **No.** Proxy-env plumbing is not ported (Android/OkHttp uses system proxy); documented boundary. |

Marginally-older commits inside/around the last audit window, verified:

| Commit | Change | Status |
| --- | --- | --- |
| `6c87d9a02` (2026-08-28 16:49) | Merge indexed Mistral tool call chunks (#8387) | **Not ported — P1-1** (landed after the last Pathfinder fix batch). |
| `5ce4afbd9` (2026-08-28) | Refresh generated image-model catalog | Text-model asset stale in the same way — P2-3. |
| `7aab6c26e` (2026-08-26) | Serialize thinking signature once (#8671) | Already ported (`applyStreamedReasoningDetails`). |
| `56f3f33a9` (2026-08-28) | Remove retired Fireworks turbo-router test | Test-only; n/a. |
| `b79e4cc83`/`853a80d26`/`bba6be972` (2026-08-28) | Release v0.84.4 / changelog chores | n/a. |

---

## Summary

- **P0**: 0 · **P1**: 3 · **P2**: 4 (one of which is a data refresh).
- The ported provider surface remains in strong shape; the P1s are two
  upstream fixes that landed at the tail end of / after the last audit window
  (Mistral #8387, Responses #8941) plus one completions compat flag the
  catalog already exercises (DeepSeek `reasoning_content`).
- The systemic risk worth closing is P2-2 (silent compat-key dropping), which
  is what let P1-2 go unnoticed.
