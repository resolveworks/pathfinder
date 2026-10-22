package works.resolve.pathfinder.ai

import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCostRates
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ModelsErrorCode
import works.resolve.pathfinder.ai.auth.ProviderAuth
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.GITHUB_COPILOT_PROVIDER_ID
import works.resolve.pathfinder.ai.providers.filterGitHubCopilotModels
import works.resolve.pathfinder.ai.utils.ClientIdentity
import works.resolve.pathfinder.ai.utils.mergeClientIdentity
import works.resolve.pathfinder.ai.utils.normalizeContext
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.HistogramOptions
import works.resolve.pathfinder.telemetry.InstrumentOptions
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_METRICS
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.TelemetryMetrics
import works.resolve.pathfinder.telemetry.TelemetrySpan
import works.resolve.pathfinder.telemetry.attr

/**
 * A resolved provider credential: the API key (or, for header-auth providers,
 * resolved auth headers), plus provider env values (e.g. Cloudflare
 * account/gateway ids) substituted into base-URL placeholders at request
 * time. Pure AI-layer type; it never exposes how the app stores credentials.
 */
class ResolvedAuth(
    /** Bearer API key; null when auth is carried entirely by [headers]. */
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** Resolved auth headers: a null value removes the header when merged. */
    val headers: Map<String, String?> = emptyMap(),
    /**
     * Credential-specific request base URL overriding the model's (e.g.
     * GitHub Copilot's per-account proxy endpoint); null keeps the model's
     * own. Not secret.
     */
    val baseUrl: String? = null
) {
    override fun toString(): String = optionsToString(
        "ResolvedAuth",
        "apiKey" to redactedSecret(apiKey),
        "env" to env.keys,
        "headers" to headers.keys,
        "baseUrl" to baseUrl
    )
}

/**
 * Provider identity, auth semantics, and model catalog; [apis] maps each
 * supported model API id to the chat implementation serving it.
 */
class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** pi's Provider.auth: static auth surface (key prompts, OAuth availability). */
    val auth: ProviderAuth? = null,
    /**
     * Resolves this provider's auth with explicit request overrides:
     * [apiKey]/[env] must be shaped by the provider's auth semantics (e.g.
     * Cloudflare's cf-aig-authorization header) WITHOUT reading stored
     * credentials; a null [apiKey] with empty [env] means resolve the stored
     * credential (explicit env still merges over stored env there). Returns
     * null when unconfigured.
     */
    val authResolver: (
        suspend (
            apiKey: String?,
            env: Map<String, String>
        ) -> ResolvedAuth?
    )? = null,
    val models: List<Model>,
    val apis: Map<String, ChatApi>
) {
    constructor(
        id: String,
        name: String,
        baseUrl: String,
        authResolver: (
            suspend (
                apiKey: String?,
                env: Map<String, String>
            ) -> ResolvedAuth?
        )? = null,
        models: List<Model>,
        apiId: String,
        api: ChatApi
    ) : this(id, name, baseUrl, null, authResolver, models, mapOf(apiId to api))
}

/**
 * The ported `models.ts` runtime: a fixed provider→API→models registry with
 * credential-resolved streaming. Divergence from pi: the dynamic half of
 * `models.ts` is not ported — no `MutableModels` (setProvider/
 * deleteProvider/clearProviders) and no `models-store.ts` `ModelsStore`/
 * `InMemoryModelsStore`, the persistence layer behind upstream
 * `refreshModels` publications (a provider fetches a remote catalog at
 * runtime and persists it with etag/lastModified for conditional
 * revalidation). Pathfinder's catalog is a bundled generated asset
 * (AGENTS.md); providers are registered once at construction and never
 * refreshed or replaced. Runtime-published model lists would need an
 * explicit decision (a store + refresh surface) if ever needed.
 */
class Models(
    providers: List<Provider>,
    /** Default identity of every request; a request's identity overrides it field by field. */
    private val identity: ClientIdentity? = null,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val byId = providers.associateBy { it.id }

    fun getProviders(): List<Provider> = byId.values.toList()

    fun getProvider(id: String): Provider? = byId[id]

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.models?.firstOrNull { it.id == modelId }

    /**
     * Whether auth is configured for a provider. Reads the stored credential
     * lazily per call, so a rotated key is observed on the next check; an
     * unknown or resolver-less provider counts as unconfigured. Resolution
     * failures propagate as [ModelsError] (`auth`), like pi's checkAuth on
     * credential-store read and API-key check failures.
     */
    suspend fun checkAuth(providerId: String): Boolean {
        val provider = byId[providerId] ?: return false
        val resolver = provider.authResolver ?: return false
        return resolver(null, emptyMap()) != null
    }

    /** pi's `isUsingOAuth`: whether the provider authenticates via OAuth. */
    fun isUsingOAuth(providerId: String): Boolean = getProvider(providerId)?.auth?.oauth != null

    companion object {
        fun modelsAreEqual(a: Model?, b: Model?): Boolean =
            a != null && b != null && a.id == b.id && a.provider == b.provider
    }

    private fun requireProvider(model: Model): Provider = byId[model.provider]
        ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: ${model.provider}")

    /**
     * pi's lazyStream setup: provider lookup, auth resolution, and adapter
     * dispatch. Throws [ModelsError] (or the adapter's own setup exception)
     * for [stream] to convert into a terminal error event.
     */
    private suspend fun resolveApiStream(
        model: Model,
        transcript: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val provider = requireProvider(model)
        // Resolve the credential inside setup so stored-credential lookups can
        // suspend without making stream() a suspend call.
        val auth = provider.authResolver?.invoke(options.apiKey, options.env)
        // A provider with an auth resolver owns auth semantics: a null
        // resolution always means unconfigured, even with an explicit
        // request key (never fall back to a raw Authorization). Only a
        // resolver-less provider may use a raw explicit apiKey directly.
        if (auth == null && (provider.authResolver != null || options.apiKey == null)) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Provider is not configured: ${model.provider}"
            )
        }
        val api = provider.apis[model.api]
            ?: throw ModelsError(
                ModelsErrorCode.STREAM,
                "Provider ${provider.id} has no API implementation for \"${model.api}\""
            )
        val requestModel = auth?.baseUrl?.let { model.copy(baseUrl = it) } ?: model
        val merged = options.copy(
            apiKey = options.apiKey ?: auth?.apiKey,
            env = if (auth == null || auth.env.isEmpty()) {
                options.env
            } else {
                auth.env + options.env
            },
            headers = mergeHeaders(auth?.headers ?: emptyMap(), options.headers),
            identity = mergeClientIdentity(identity, options.identity)
        )
        return api.streamSimple(requestModel, transcript, merged)
    }

    /**
     * Starts a chat stream for [model]: the model's provider must be
     * registered and implement the model's API. Auth is resolved lazily
     * inside the flow and merged with [options]; the explicit [options]
     * apiKey/env still pass through the provider's auth resolver so custom
     * auth shaping (Cloudflare's header auth) applies to them, without
     * reading stored credentials. Explicit request fields win: an explicit
     * apiKey overrides the resolved one, env values merge per field with the
     * request on top, and resolved auth headers merge under explicit request
     * headers case-insensitively.
     *
     * pi's lazyStream contract: setup failures — unknown provider, absent or
     * failing credential resolution, a missing API implementation, or a
     * synchronous setup throw from the API adapter (streamSimple may throw
     * for missing request auth) — surface as a single terminal
     * [AssistantMessageEvent.Error] event carrying the ModelsError message,
     * never as an exception from this method or the returned flow.
     */
    fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions()
    ): Flow<AssistantMessageEvent> {
        val transcript = normalizeContext(context)
        val startedAt = clock.now().toEpochMilliseconds()
        val startedMark = timeSource.markNow()
        return flow {
            val startAttributes = buildMap {
                put("provider", attr(model.provider))
                put("api", attr(model.api))
                put("model", attr(model.id))
                options.reasoning?.let { put("reasoning", attr(it.name.lowercase())) }
            }
            val parent = options.telemetry ?: NOOP_TELEMETRY_CONTEXT
            parent.startSpan(SpanOptions("pi.ai.request", startAttributes)) { span ->
                val observer = ModelRequestObserver(model, startedMark, span)
                val apiStream = try {
                    resolveApiStream(model, transcript, options)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = setupError(model, error, startedAt)
                    val event = AssistantMessageEvent.Error(StopReason.ERROR, message)
                        .withStreamDuration(startedAt, startedMark)
                    emit(event)
                    observer.onEvent(event)
                    observer.onResult(message)
                    return@startSpan
                }
                var result: AssistantMessage? = null
                apiStream.collect { raw ->
                    val event = raw.withStreamDuration(startedAt, startedMark)
                    emit(event)
                    observer.onEvent(event)
                    when (event) {
                        is AssistantMessageEvent.Done -> result = event.message
                        is AssistantMessageEvent.Error -> result = event.error
                        else -> {}
                    }
                }
                observer.onResult(result)
            }
        }
    }

    /**
     * Collects [stream] to its terminal AssistantMessage. A terminal `Error`
     * event is returned as the error AssistantMessage, so callers inspect
     * [AssistantMessage.stopReason] to distinguish failure from success;
     * like pi's `result()` promise, a stream that ends without a terminal
     * event never resolves.
     */
    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions()
    ): AssistantMessage {
        val terminal = CompletableDeferred<AssistantMessage>()
        stream(model, context, options).collect { event ->
            when (event) {
                is AssistantMessageEvent.Done -> terminal.complete(event.message)
                is AssistantMessageEvent.Error -> terminal.complete(event.error)
                else -> {}
            }
        }
        return terminal.await()
    }

    /** pi's lazyStream createSetupErrorMessage: zeroed usage, error stop, stream-start timestamp. */
    private fun setupError(model: Model, error: Exception, timestamp: Long): AssistantMessage =
        AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.ERROR,
            errorMessage = error.message,
            timestamp = timestamp
        )
}

private class RequestInstruments(metrics: TelemetryMetrics) {
    val requestDuration = metrics.histogram(
        "pi.ai.request.duration",
        HistogramOptions(description = "Logical request or poll duration.", unit = "ms")
    )
    val firstChunkDuration = metrics.histogram(
        "pi.ai.first_chunk.duration",
        HistogramOptions(description = "Time to the first normalized stream event.", unit = "ms")
    )
    val tokens = metrics.counter(
        "pi.ai.tokens",
        InstrumentOptions(
            description = "Reported model token usage; pending and deferred polls are excluded.",
            unit = "token"
        )
    )
    val cost = metrics.counter(
        "pi.ai.cost",
        InstrumentOptions(
            description = "Reported model cost; pending and deferred polls are excluded.",
            unit = "USD"
        )
    )
}

private val instrumentCacheLock = Any()
private val instrumentCache =
    Collections.synchronizedMap(WeakHashMap<TelemetryMetrics, RequestInstruments>())

private fun requestInstruments(metrics: TelemetryMetrics): RequestInstruments =
    synchronized(instrumentCacheLock) {
        instrumentCache.getOrPut(metrics) { RequestInstruments(metrics) }
    }

/**
 * pi's `observeModelRequest` for the Models stream path. It records metadata
 * and bounded block sizes, never message content, tool arguments, or error
 * text beyond the status message.
 */
private class ModelRequestObserver(
    model: Model,
    private val started: TimeMark,
    private val span: TelemetrySpan
) {
    private val attributes = mapOf("provider" to attr(model.provider), "model" to attr(model.id))
    private val instruments = requestInstruments(span.metrics ?: NOOP_TELEMETRY_METRICS)
    private val toolChars = mutableMapOf<Int, Int>()
    private var first = true

    fun onEvent(event: AssistantMessageEvent) {
        if (first) {
            first = false
            span.addEvent("pi.ai.first_chunk")
            instruments.firstChunkDuration.record(elapsedMs(), attributes, span)
        }
        when (event) {
            is AssistantMessageEvent.TextEnd -> span.addEvent(
                "pi.ai.block",
                mapOf(
                    "type" to attr("text"),
                    "index" to attr(event.contentIndex),
                    "chars" to attr(event.content.length)
                )
            )

            is AssistantMessageEvent.ThinkingEnd -> span.addEvent(
                "pi.ai.block",
                mapOf(
                    "type" to attr("thinking"),
                    "index" to attr(event.contentIndex),
                    "chars" to attr(event.content.length)
                )
            )

            is AssistantMessageEvent.ToolCallDelta -> {
                toolChars[event.contentIndex] =
                    (toolChars[event.contentIndex] ?: 0) + event.delta.length
            }

            is AssistantMessageEvent.ToolCallEnd -> {
                val chars = toolChars.remove(event.contentIndex) ?: 0
                span.addEvent(
                    "pi.ai.block",
                    mapOf(
                        "type" to attr("tool_call"),
                        "index" to attr(event.contentIndex),
                        "chars" to attr(chars),
                        "tool" to attr(event.toolCall.name)
                    )
                )
            }

            else -> {}
        }
    }

    fun onResult(message: AssistantMessage?) {
        if (message == null) return
        val stop = message.stopReason.telemetryWire()
        span.setAttributes(
            buildMap {
                put("stop", attr(stop))
                put("usage.input", attr(message.usage.input))
                put("usage.output", attr(message.usage.output))
                put("usage.cache_read", attr(message.usage.cacheRead))
                put("usage.cache_write", attr(message.usage.cacheWrite))
                put("cost.total", attr(message.usage.cost.total))
                message.responseId?.let { put("response.id", attr(it)) }
            }
        )
        if (message.stopReason == StopReason.ERROR) {
            span.setStatus(
                SpanStatus.Error(
                    TelemetryError("provider_error", message.errorMessage?.take(500) ?: "")
                )
            )
        }
        instruments.requestDuration.record(
            elapsedMs(),
            attributes + ("stop" to attr(stop)),
            span
        )
        if (message.stopReason != StopReason.PENDING && message.stopReason != StopReason.DEFERRED) {
            instruments.tokens.add(
                message.usage.input.toDouble(),
                attributes + ("kind" to attr("input"))
            )
            instruments.tokens.add(
                message.usage.output.toDouble(),
                attributes + ("kind" to attr("output"))
            )
            instruments.tokens.add(
                message.usage.cacheRead.toDouble(),
                attributes + ("kind" to attr("cache_read"))
            )
            instruments.tokens.add(
                message.usage.cacheWrite.toDouble(),
                attributes + ("kind" to attr("cache_write"))
            )
            instruments.cost.add(message.usage.cost.total, attributes)
        }
    }

    private fun elapsedMs(): Double = started.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
}

private fun StopReason.telemetryWire(): String = when (this) {
    StopReason.PENDING -> "pending"
    StopReason.STOP -> "stop"
    StopReason.LENGTH -> "length"
    StopReason.TOOL_USE -> "toolUse"
    StopReason.ERROR -> "error"
    StopReason.ABORTED -> "aborted"
    StopReason.DEFERRED -> "deferred"
}

private fun AssistantMessageEvent.withStreamDuration(
    startedAt: Long,
    startedMark: TimeMark
): AssistantMessageEvent = when (this) {
    is AssistantMessageEvent.Done -> copy(
        message = message.withStreamDuration(startedAt, startedMark)
    )

    is AssistantMessageEvent.Error -> copy(
        error = error.withStreamDuration(startedAt, startedMark)
    )

    else -> this
}

private fun AssistantMessage.withStreamDuration(
    startedAt: Long,
    startedMark: TimeMark
): AssistantMessage {
    if (durationMs != null || timestamp < startedAt) return this
    val elapsed = startedMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
    return copy(durationMs = elapsed.roundToLong().coerceAtLeast(0))
}

/**
 * Reference cost in USD from token usage and per-million rates. The tier with
 * the highest `inputTokensAbove` threshold that the request's total input
 * usage strictly exceeds applies its rates to the full request.
 */
fun calculateCost(model: Model, usage: Usage): Cost {
    val inputTokens = usage.input + usage.cacheRead + usage.cacheWrite
    var rates: ModelCostRates = model.cost
    var matchedThreshold = -1
    for (tier in model.cost.tiers) {
        if (inputTokens > tier.inputTokensAbove && tier.inputTokensAbove > matchedThreshold) {
            rates = tier
            matchedThreshold = tier.inputTokensAbove
        }
    }

    // Anthropic charges 2x base input for 1h cache writes.
    val longWrite = usage.cacheWrite1h
    val shortWrite = usage.cacheWrite - longWrite
    val cost = Cost(
        input = (rates.input / 1_000_000.0) * usage.input,
        output = (rates.output / 1_000_000.0) * usage.output,
        cacheRead = (rates.cacheRead / 1_000_000.0) * usage.cacheRead,
        cacheWrite = (rates.cacheWrite * shortWrite + rates.input * 2 * longWrite) / 1_000_000.0,
        totalFromProvider = false
    )
    return cost.copy(total = cost.input + cost.output + cost.cacheRead + cost.cacheWrite)
}

private val EXTENDED_THINKING_LEVELS = listOf(
    ModelThinkingLevel.OFF,
    ModelThinkingLevel.MINIMAL,
    ModelThinkingLevel.LOW,
    ModelThinkingLevel.MEDIUM,
    ModelThinkingLevel.HIGH,
    ModelThinkingLevel.XHIGH,
    ModelThinkingLevel.MAX
)

fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel> {
    if (!model.reasoning) return listOf(ModelThinkingLevel.OFF)
    val map = model.thinkingLevelMap
    return EXTENDED_THINKING_LEVELS.filter { level ->
        val mapped = map?.forLevel(level)
        val specified = map?.isSpecified(level) == true
        // Explicit null means unsupported.
        if (specified && mapped == null) return@filter false
        // XHIGH/MAX require an explicit non-null mapping; OFF..HIGH default to supported.
        if (level == ModelThinkingLevel.XHIGH || level == ModelThinkingLevel.MAX) {
            return@filter specified && mapped != null
        }
        true
    }
}

/**
 * Clamps a requested thinking level to one the model supports, rounding up
 * first, then down.
 */
fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel {
    val available = getSupportedThinkingLevels(model)
    if (available.contains(level)) return level

    val requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level)
    if (requestedIndex == -1) return available.firstOrNull() ?: ModelThinkingLevel.OFF

    for (i in requestedIndex until EXTENDED_THINKING_LEVELS.size) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    for (i in requestedIndex - 1 downTo 0) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    return available.firstOrNull() ?: ModelThinkingLevel.OFF
}

/**
 * pi's per-provider `filterModels` hook dispatch, which `getAvailable`
 * applies as `provider.filterModels?.(models, credential) ?? models`
 * (models.ts L538 at pin b8b873b98); GitHub Copilot alone defines the hook.
 * The dispatch lives in this free function instead of a `filterModels`
 * field on [CatalogProvider] because the catalog is generated data, while
 * the filter is runtime behavior tied to the credential shape.
 */
fun filterCatalogModels(provider: CatalogProvider, credential: Credential?): List<Model> =
    if (provider.id == GITHUB_COPILOT_PROVIDER_ID) {
        filterGitHubCopilotModels(provider.models, credential)
    } else {
        provider.models
    }
