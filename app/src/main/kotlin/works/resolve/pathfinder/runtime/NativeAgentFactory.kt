package works.resolve.pathfinder.runtime

import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.providers.normalizeBaseUrl
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthResolutionOverrides
import works.resolve.pathfinder.ai.auth.CatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Production [AgentFactory]: builds the native agent from the persisted
 * configuration, serving any provider/model pair the generated catalog knows.
 *
 * Divergences from pi (differences.md §5.1, both accepted):
 * - pi's agent package resolves its stream function through a module-level
 *   mutable default (`stream-fn.ts`: `setDefaultStreamFn`/
 *   `getDefaultStreamFn`, falling back to it when callers omit `streamFn`).
 *   This port replaces the ambient global with constructor injection: the
 *   Models-backed [StreamFn] is wired here into [Agent]/[AgentLoopConfig],
 *   which take it as a required parameter — no process-wide mutable state,
 *   and a missing stream function is a compile error, not pi's runtime
 *   `getDefaultStreamFn()` throw.
 * - pi's `utils/event-stream.ts` (`EventStream`, a queue-backed async
 *   iterable with an out-of-band final result) is replaced by Kotlin
 *   `Flow&lt;AssistantMessageEvent&gt;`: the stream contract in
 *   `ai/core/Types.kt` (failures terminate the flow as a terminal Error
 *   event) carries both the iteration and the final-result roles.
 *
 * Credentials are read once per request inside Models.stream's lazy flow, so
 * a rotated or completed credential takes effect on the next prompt. Stored
 * API-key and OAuth credentials resolve through [resolveProviderAuth] with
 * the catalog-backed provider auth. The credential never enters the agent,
 * its options, or any log; an incomplete or unhandled credential resolves
 * to null (defense in depth beyond [CatalogProvider.isCredentialComplete])
 * and surfaces as a single safe Error event from Models.
 */
class NativeAgentFactory(
    private val credentials: CredentialStore,
    private val catalog: ProviderCatalog,
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    /** WebSocket transport for the Codex adapter; null disables the WebSocket path. */
    private val webSocketTransport: WebSocketStreamingTransport? = null,
    /** Android has no ambient env; tests inject real [AuthContext]s. */
    private val authContext: AuthContext = NoopAuthContext,
    private val authRegistry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
    /** Tools available to every created agent; copied per agent. */
    private val tools: List<AgentTool> = emptyList(),
) : AgentFactory {

    override fun create(
        settings: ModelSettings,
        sessionId: String,
        conversation: Conversation,
    ): AgentSession {
        val entry = catalog.getProvider(settings.providerId)
            ?: throw IllegalArgumentException("Unsupported provider: ${settings.providerId}")
        val model = entry.model(settings.modelId)
            ?: throw IllegalArgumentException(
                "Unknown model '${settings.modelId}' for provider '${settings.providerId}'",
            )
        // Fail fast on APIs without a Kotlin implementation; streaming
        // would reject them too.
        require(ChatApiRegistry.isSupported(model.api)) {
            "Unsupported API '${model.api}' for provider '${settings.providerId}' (model '${settings.modelId}')"
        }
        val effectiveModel = model.copy(baseUrl = normalizeBaseUrl(model.baseUrl))

        // Register every catalog provider, not just the initial one: the
        // models stack is what makes live model switching
        // (AgentSession.setModel) work across providers — the next prompt
        // resolves auth and the API from the same stack.
        val models = Models(
            catalog.providers.map { entry ->
                entry.toRuntimeProvider(
                    transport = transport,
                    retry = retry,
                    authResolver = catalogAuthResolver(entry, credentials, authContext, authRegistry),
                    webSocketTransport = webSocketTransport,
                )
            },
        )

        return AgentSession(
            agent = Agent(
                model = effectiveModel,
                tools = tools.toList(),
                streamOptions = SimpleStreamOptions(
                    sessionId = sessionId,
                    timeoutMs = REQUEST_TIMEOUT_MS,
                    maxRetries = MAX_RETRIES,
                ),
                streamFn = StreamFn { requestedModel, context, options ->
                    models.stream(requestedModel, context, options)
                },
            ),
            conversation = conversation,
            retrySettings = settings.retry,
            compactionSettings = settings.compaction,
            models = models,
            tools = tools.toList(),
        )
    }

    /**
     * Resolves a catalog provider/model pair to the effective request model,
     * validating provider, model, and API support with the same errors as
     * [create] and stamping the normalized base URL. The seam for live
     * switching: callers pass the result to [AgentSession.setModel].
     */
    fun resolveModel(providerId: String, modelId: String): Model {
        val entry = catalog.getProvider(providerId)
            ?: throw IllegalArgumentException("Unsupported provider: $providerId")
        val model = entry.model(modelId)
            ?: throw IllegalArgumentException("Unknown model '$modelId' for provider '$providerId'")
        require(ChatApiRegistry.isSupported(model.api)) {
            "Unsupported API '${model.api}' for provider '$providerId' (model '$modelId')"
        }
        return model.copy(baseUrl = normalizeBaseUrl(model.baseUrl))
    }
}

/**
 * An explicit request key/env is shaped by the provider's auth semantics
 * without reading stored credentials; otherwise the stored credential is
 * read per request with explicit env merged over stored env per field before
 * completeness and shaping. A stored OAuth credential resolves (and
 * refreshes) through the provider's registered OAuth flow, when one is
 * registered. Returns null when unconfigured.
 */
internal fun catalogAuthResolver(
    entry: CatalogProvider,
    credentials: CredentialStore,
    authContext: AuthContext = NoopAuthContext,
    authRegistry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
): suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth? =
    { explicitKey, explicitEnv ->
        val overrides =
            if (explicitKey == null && explicitEnv.isEmpty()) {
                null
            } else {
                AuthResolutionOverrides(apiKey = explicitKey, env = explicitEnv)
            }
        resolveProviderAuth(
            provider = CatalogAuthProviderRef(entry, authRegistry),
            credentials = credentials,
            authContext = authContext,
            overrides = overrides,
        )?.let { result ->
            ResolvedAuth(
                apiKey = result.auth.apiKey,
                env = result.env,
                headers = result.auth.headers,
                baseUrl = result.auth.baseUrl,
            )
        }
    }

/** Finite per-request timeout (covers headers through stream end via the call timeout). */
private const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

/** Minimal retry budget chosen by the app (pi provider-retry defaults to 0);
 * one retry keeps worst-case request duration bounded on mobile. */
private const val MAX_RETRIES = 1
