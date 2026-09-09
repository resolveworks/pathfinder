package works.resolve.pathfinder.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthResolutionOverrides
import works.resolve.pathfinder.ai.auth.CatalogAuthProviderRef
import works.resolve.pathfinder.ai.auth.CatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.CatalogProviderAuth
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.providers.normalizeBaseUrl
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.CreateAgentSessionResult
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.codingagent.core.createAgentSession
import works.resolve.pathfinder.codingagent.core.tools.BashToolOptions
import works.resolve.pathfinder.codingagent.core.tools.EditToolOptions
import works.resolve.pathfinder.codingagent.core.tools.ImageProcessing
import works.resolve.pathfinder.codingagent.core.tools.ReadToolOptions
import works.resolve.pathfinder.codingagent.core.tools.ToolsOptions
import works.resolve.pathfinder.codingagent.core.tools.WriteToolOptions
import works.resolve.pathfinder.codingagent.core.tools.createCodingTools
import works.resolve.pathfinder.ssh.RemoteBashOperations
import works.resolve.pathfinder.ssh.RemoteFileOperations
import works.resolve.pathfinder.ssh.SshConnectionProvider

/**
 * Production [AgentFactory]: builds the native agent stack from the persisted
 * configuration, serving any provider/model pair the generated catalog knows;
 * model resolution, restoration, and seeding are owned by the core
 * createAgentSession factory.
 *
 * Divergences from pi (both accepted):
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
 *   `packages/ai/.../ai/Types.kt` (failures terminate the flow as a terminal
 *   Error event) carries both the iteration and the final-result roles.
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
    /** Shared process-wide settings manager; passed straight to created sessions. */
    private val settingsManager: SettingsManager,
    private val retry: ProviderRetry = ProviderRetry(),
    /** WebSocket transport for the Codex adapter; null disables the WebSocket path. */
    private val webSocketTransport: WebSocketStreamingTransport? = null,
    /** Android has no ambient env; tests inject real [AuthContext]s. */
    private val authContext: AuthContext = NoopAuthContext,
    private val authRegistry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
    /** Tools available to every created agent; copied per agent. */
    private val tools: List<AgentTool> = emptyList(),
    /** Process-wide SSH connection provider; when null, sessions get no coding tools. */
    private val sshConnectionProvider: SshConnectionProvider? = null,
    /** Bash full-output temp dir (the app's cacheDir). */
    private val bashTempDir: String? = null,
    /** Image codec for the read tool's image path. */
    private val imageProcessing: ImageProcessing? = null
) : AgentFactory {

    override suspend fun create(sessionManager: SessionManager): CreateAgentSessionResult {
        // Register every catalog provider, not just the initial one: the
        // models stack is what makes live model switching
        // (AgentSession.setModel) work across providers — the next prompt
        // resolves auth and the API from the same stack.
        val models = Models(
            catalog.providers.map { entry ->
                entry.toRuntimeProvider(
                    transport = transport,
                    retry = retry,
                    authResolver = catalogAuthResolver(
                        entry,
                        credentials,
                        authContext,
                        authRegistry
                    ),
                    webSocketTransport = webSocketTransport,
                    auth = CatalogProviderAuth(entry, authRegistry)
                )
            }
        )

        val ssh = sshCodingTools()
        val codingTools = ssh?.tools ?: emptyList()
        // pi's ssh example annotates the remote cwd as `... (via SSH: user@host)`
        // in the system prompt; the same string is the session's persisted cwd.
        val cwd = ssh?.cwd ?: ""
        sessionManager.updateCwd(cwd)

        val result =
            createAgentSession(
                manager = sessionManager,
                settingsManager = settingsManager,
                models = models,
                streamFn = StreamFn { requestedModel, context, options ->
                    // Request encoding and stream decoding run off Main; agent/session
                    // state and tool execution stay on the loop's dispatcher. A small
                    // bounded buffer keeps memory bounded per token-snapshot rate
                    // mismatch without rendezvous-coupling SSE delivery to the
                    // downstream consumer's speed; default SUSPEND overflow applies.
                    models.stream(requestedModel, context, options)
                        .buffer(STREAM_BUFFER_CAPACITY)
                        .flowOn(Dispatchers.Default)
                },
                tools = tools.toList() + codingTools,
                cwd = cwd,
                streamOptions = SimpleStreamOptions(
                    sessionId = sessionManager.getSessionId(),
                    timeoutMs = REQUEST_TIMEOUT_MS,
                    maxRetries = MAX_RETRIES
                )
            )
        ssh?.createdSession?.set(result.session)
        return result
    }

    /**
     * Builds the ported coding tools against the currently selected
     * machine's configured cwd, or null when no machine is configured (or no
     * provider is wired). Tools dial lazily per call; nothing connects
     * here. The session's model is not known until createAgentSession builds
     * it, so the read tool's model provider reads it through
     * [AtomicReference] filled once creation returns.
     */
    private suspend fun sshCodingTools(): SshTools? {
        val provider = sshConnectionProvider ?: return null
        val tempDir = bashTempDir ?: return null

        val machine = provider.currentMachine() ?: return null
        val files = RemoteFileOperations(provider)
        val createdSession = AtomicReference<AgentSession>()
        val tools = createCodingTools(
            cwd = machine.cwd,
            options = ToolsOptions(
                read = ReadToolOptions(
                    operations = files,
                    imageProcessing = imageProcessing,
                    modelProvider = { createdSession.get()?.model }
                ),
                bash = BashToolOptions(RemoteBashOperations(provider), tempDir),
                edit = EditToolOptions(files),
                write = WriteToolOptions(files)
            )
        )
        return SshTools(
            tools,
            "${machine.cwd} (via SSH: ${machine.username}@${machine.address})",
            createdSession
        )
    }

    /** Coding tools plus the cwd string and slot the created session lands in for the read tool's model provider. */
    private class SshTools(
        val tools: List<AgentTool>,
        val cwd: String,
        val createdSession: AtomicReference<AgentSession>
    )

    /**
     * Resolves a catalog provider/model pair to the effective request model,
     * validating provider, model, and API support and stamping the normalized base URL. The seam for live
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
    authRegistry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY
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
            overrides = overrides
        )?.let { result ->
            ResolvedAuth(
                apiKey = result.auth.apiKey,
                env = result.env,
                headers = result.auth.headers,
                baseUrl = result.auth.baseUrl
            )
        }
    }

/** Bounded handoff buffer between the network stream and the agent loop collector. */
private const val STREAM_BUFFER_CAPACITY = 64

/** Finite per-request timeout (covers headers through stream end via the call timeout). */
private const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

/** Minimal retry budget chosen by the app (pi provider-retry defaults to 0);
 * one retry keeps worst-case request duration bounded on mobile. */
private const val MAX_RETRIES = 1
