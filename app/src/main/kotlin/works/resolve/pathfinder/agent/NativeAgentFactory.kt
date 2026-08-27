package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.providers.normalizeBaseUrl
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthResolutionOverrides
import works.resolve.pathfinder.ai.auth.CatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Production [AgentFactory]: builds the native agent from the persisted
 * configuration, serving any provider/model pair the generated catalog knows.
 *
 * All credential ownership lives behind the ported auth resolution (pi's
 * auth.resolve): credentials are read once per request inside Models.stream's
 * lazy flow, so a rotated or completed credential takes effect on the next
 * prompt. Both stored API-key and OAuth credentials resolve through
 * [resolveProviderAuth] with the catalog-backed provider auth; explicit
 * request overrides keep pi's precedence (explicit key wins, explicit env
 * merges over stored env per field). The credential never enters the agent,
 * its options, or any log; an incomplete or unhandled credential resolves to
 * null (defense in depth on top of the UI's CatalogProvider.isCredentialComplete
 * gate) and surfaces as a single safe Error event from Models.
 */
class NativeAgentFactory(
    private val credentials: CredentialStore,
    private val catalog: ProviderCatalog,
    private val transport: HttpStreamingTransport,
    private val retry: ProviderRetry = ProviderRetry(),
    /** Android has no ambient env; tests inject real [AuthContext]s. */
    private val authContext: AuthContext = NoopAuthContext,
    /** Maps catalog OAuth-capable providers to concrete flows (empty for now). */
    private val authRegistry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
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
        // Fail fast on APIs without a Kotlin implementation (the catalog
        // carries all of pi's model APIs); streaming would reject it too.
        require(ChatApiRegistry.isSupported(model.api)) {
            "Unsupported API '${model.api}' for provider '${settings.providerId}' (model '${settings.modelId}')"
        }
        // The selected effective model is created once (pi's requestModel):
        // the catalog model with its normalized base URL.
        val effectiveModel = model.copy(baseUrl = normalizeBaseUrl(model.baseUrl))

        val provider = entry.toRuntimeProvider(
            transport = transport,
            retry = retry,
            authResolver = catalogAuthResolver(entry, credentials, authContext, authRegistry),
        )
        val models = Models(listOf(provider))

        // Session facade over the single-run agent (pi's AgentSession over
        // Agent): retry budget from settings, tree ownership included.
        return AgentSession(
            agent = Agent(
                model = effectiveModel,
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
        )
    }
}

/**
 * The factory's provider auth resolver (pi's auth.resolve with overrides):
 * an explicit request key/env is shaped by the provider's auth semantics
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
