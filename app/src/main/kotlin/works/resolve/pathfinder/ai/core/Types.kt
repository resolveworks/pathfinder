package works.resolve.pathfinder.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.api.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.TelemetryContext

/**
 * Twin of pi's `packages/ai/src/types.ts` (one file upstream; sections here
 * follow its order): thinking levels, cache retention and transport, content
 * and message types, usage/tool-choice/context/tool/constrained-sampling,
 * the assistant-message event protocol and stream options, the model shape
 * with cost and compat settings, and the [ChatApi] stream contract
 * (upstream `ProviderStreams.streamSimple`).
 */

enum class ThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/**
 * Thinking level including "off". [wire] is the persisted wire name
 * (`thinking_level_change` session entries and settings).
 */
enum class ModelThinkingLevel(val wire: String) {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
}

/**
 * Decodes a thinking-level wire string (session entries, settings); null
 * for unknown values — never `valueOf` untrusted input.
 */
fun modelThinkingLevelFromWire(wire: String): ModelThinkingLevel? = when (wire) {
    "off" -> ModelThinkingLevel.OFF
    "minimal" -> ModelThinkingLevel.MINIMAL
    "low" -> ModelThinkingLevel.LOW
    "medium" -> ModelThinkingLevel.MEDIUM
    "high" -> ModelThinkingLevel.HIGH
    "xhigh" -> ModelThinkingLevel.XHIGH
    "max" -> ModelThinkingLevel.MAX
    else -> null
}

/**
 * Mapping up is total — every [ThinkingLevel] names exactly one
 * [ModelThinkingLevel]. Explicit `when` (not `valueOf(name)`) so a new
 * upstream level forces an update here instead of failing at runtime.
 */
fun ThinkingLevel.toModelThinkingLevel(): ModelThinkingLevel = when (this) {
    ThinkingLevel.MINIMAL -> ModelThinkingLevel.MINIMAL
    ThinkingLevel.LOW -> ModelThinkingLevel.LOW
    ThinkingLevel.MEDIUM -> ModelThinkingLevel.MEDIUM
    ThinkingLevel.HIGH -> ModelThinkingLevel.HIGH
    ThinkingLevel.XHIGH -> ModelThinkingLevel.XHIGH
    ThinkingLevel.MAX -> ModelThinkingLevel.MAX
}

/**
 * Down direction of the `"off" | ThinkingLevel` union: OFF has no
 * [ThinkingLevel], so it maps to null and callers decide what "off" means
 * at their boundary.
 */
fun ModelThinkingLevel.toThinkingLevelOrNull(): ThinkingLevel? = when (this) {
    ModelThinkingLevel.OFF -> null
    ModelThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
    ModelThinkingLevel.LOW -> ThinkingLevel.LOW
    ModelThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
    ModelThinkingLevel.HIGH -> ThinkingLevel.HIGH
    ModelThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
    ModelThinkingLevel.MAX -> ThinkingLevel.MAX
}

enum class CacheRetention { SHORT, LONG, NONE }

/**
 * Only the Codex adapter consumes the transport choice; other APIs ignore
 * it. AUTO is WebSocket-first with per-session SSE fallback.
 */
enum class Transport { SSE, WEBSOCKET, WEBSOCKET_CACHED, AUTO }

sealed class Content {
    abstract val type: ContentType
}

enum class ContentType { TEXT, THINKING, IMAGE, TOOL_CALL }

data class TextContent(
    val text: String,
    /**
     * Opaque thought-signature replay data Google attaches to a text part;
     * only meaningful for the same provider/model.
     */
    val textSignature: String? = null,
) : Content() {
    override val type: ContentType get() = ContentType.TEXT
}

data class ThinkingContent(
    val thinking: String,
    /** Provider-specific opaque reasoning replay data (e.g. which wire field it came from). */
    val thinkingSignature: String? = null,
    /** True for Anthropic redacted_thinking blocks: opaque replay-only payload. */
    val redacted: Boolean = false,
) : Content() {
    override val type: ContentType get() = ContentType.THINKING
}

data class ImageContent(
    /** Base64 encoded image data. */
    val data: String,
    val mimeType: String,
) : Content() {
    override val type: ContentType get() = ContentType.IMAGE
}

data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON arguments string exactly as the provider streamed/replayed them. */
    val arguments: String,
    /**
     * Opaque thought-signature replay data Google attaches to a functionCall
     * part; only meaningful for the same provider/model.
     */
    val thoughtSignature: String? = null,
    /** OpenAI Responses namespace for dynamically loaded or namespaced tools. */
    val namespace: String? = null,
) : Content() {
    override val type: ContentType get() = ContentType.TOOL_CALL
}

sealed class Message {
    abstract val role: MessageRole
    abstract val timestamp: Long
}

enum class MessageRole { USER, ASSISTANT, TOOL_RESULT }

data class UserMessage(
    // Reduction: pi's content also allows a plain string; the port accepts
    // only the structured array form — use [ofText] for the plain-string shape.
    val content: List<Content>,
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.USER

    companion object {
        fun ofText(text: String, timestamp: Long = 0L) =
            UserMessage(listOf(TextContent(text)), timestamp)
    }
}

data class AssistantMessage(
    val content: List<Content>,
    /** API implementation identifier, e.g. "openai-completions". */
    val api: String,
    val provider: String,
    val model: String,
    val usage: Usage = Usage(),
    val stopReason: StopReason = StopReason.PENDING,
    val errorMessage: String? = null,
    val rawStopReason: String? = null,
    val responseId: String? = null,
    val responseModel: String? = null,
    /** Codex end-of-turn flag from the terminal response. */
    val endTurn: Boolean? = null,
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.ASSISTANT
}

data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<Content>,
    /**
     * Arbitrary structured runtime/UI metadata attached to the result,
     * preserved verbatim; not a wire field — no provider adapter reads it.
     */
    val details: JsonElement? = null,
    /**
     * Usage from the tool execution itself, if available; not part of main
     * LLM context accounting.
     */
    val usage: Usage? = null,
    val isError: Boolean = false,
    /** Tool names this result made available (deferred tool loading). */
    val addedToolNames: List<String> = emptyList(),
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.TOOL_RESULT
}

data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    /**
     * Subset of `cacheWrite` written with 1h retention; only Anthropic
     * reports this split — non-Anthropic adapters leave it at 0.
     */
    val cacheWrite1h: Int = 0,
    val reasoning: Int = 0,
    val totalTokens: Int = 0,
    val cost: Cost = Cost(),
)

data class Cost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
    val total: Double = 0.0,
)

/** No provider adapter produces DEFERRED; it exists because the session
 * layer drops deferred assistant messages from context. */
enum class StopReason { PENDING, STOP, LENGTH, TOOL_USE, ERROR, ABORTED, DEFERRED }

/** OpenAI grammar variants for constrained sampling. */
enum class GrammarFormat { OPENAI_LARK, OPENAI_REGEX }

typealias GrammarVariants = Map<GrammarFormat, String>

enum class StrictJsonSchemaMode { PREFER, REQUIRE }

/**
 * Optional provider-side constrained sampling configs for a tool. The
 * `json_schema` value roughly maps to the concept of `strict` in APIs
 * which is implemented as json-schema constrained sampling by APIs;
 * grammar variants let callers provide provider-specific encodings of the
 * same intended language.
 *
 * pi models three states (unset, explicit `false`, config) in one union on
 * `Tool.constrainedSampling`; here unset is `null` and `false` is
 * [Disabled].
 */
sealed interface ConstrainedSamplingConfig {
    data object Disabled : ConstrainedSamplingConfig

    data class JsonSchema(val strict: StrictJsonSchemaMode) : ConstrainedSamplingConfig

    data class Grammar(val variants: Map<GrammarFormat, String>) : ConstrainedSamplingConfig
}

/** Tool definition; parameters is a JSON Schema object. */
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonElement,
    val constrainedSampling: ConstrainedSamplingConfig? = null,
)

/**
 * Narrow tool-selection union for the simple API. The full union
 * ([ToolChoice]) is accepted only by the OpenAI-completions options.
 */
sealed interface SimpleToolChoice {
    data object Auto : SimpleToolChoice
    data object None : SimpleToolChoice
}

fun SimpleToolChoice.toToolChoice(): ToolChoice = when (this) {
    SimpleToolChoice.Auto -> ToolChoice.Auto
    SimpleToolChoice.None -> ToolChoice.None
}

/**
 * Full tool-selection union (OpenAI's ChatCompletionToolChoiceOption:
 * "auto" | "none" | "required" | {type:"function"...}). Also carried by
 * provider-specific options types whose pi counterparts define their own
 * broader unions. The simple API must not accept these values; it uses
 * [SimpleToolChoice].
 */
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Any : ToolChoice
    data object Required : ToolChoice
    /** Force a specific named function tool. */
    data class Function(val name: String) : ToolChoice
}

data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<Tool> = emptyList(),
)

/**
 * A successful stream emits `Start` first, then block events carrying
 * immutable partial snapshots, and terminates with `Done`. Failures at any
 * point — including auth or setup failures before anything is emitted — are
 * encoded as a terminal `Error` event, which may therefore arrive without a
 * preceding `Start`. `Done` and `Error` are mutually exclusive terminal
 * events.
 *
 * Coroutine cancellation is not a failure: cancelling the collecting
 * coroutine propagates normally (the flow simply stops emitting) and no
 * `Error` event is produced.
 */
sealed class AssistantMessageEvent {
    abstract val partial: AssistantMessage

    data class Start(override val partial: AssistantMessage) : AssistantMessageEvent()

    data class TextStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class TextDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class TextEnd(val contentIndex: Int, val content: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ThinkingEnd(val contentIndex: Int, val content: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallStart(val contentIndex: Int, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallDelta(val contentIndex: Int, val delta: String, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class ToolCallEnd(val contentIndex: Int, val toolCall: ToolCall, override val partial: AssistantMessage) :
        AssistantMessageEvent()

    data class Done(
        val reason: StopReason,
        val message: AssistantMessage,
    ) : AssistantMessageEvent() {
        override val partial: AssistantMessage get() = message
    }

    data class Error(
        val reason: StopReason,
        /** Final assistant message with stopReason ABORTED/ERROR and errorMessage set. */
        val error: AssistantMessage,
    ) : AssistantMessageEvent() {
        override val partial: AssistantMessage get() = error
    }
}

data class ProviderResponse(
    val status: Int,
    val headers: Map<String, String>,
)

/**
 * Flattens multi-valued HTTP response headers into the single-value map
 * passed to `onResponse` hooks; repeated values are joined with ", " to
 * match WHATWG fetch header-value combining. Names arrive already
 * lower-cased from the transport.
 */
fun headersToRecord(headers: Map<String, List<String>>): Map<String, String> =
    headers.mapValues { (_, values) -> values.joinToString(", ") }

data class StreamOptions(
    /** Explicit API key; when absent the provider's credential resolver is used. Never included in toString(). */
    val apiKey: String? = null,
    /** Session identifier usable for affinity/sticky routing. */
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    /** Cap on server-requested retry delays; delays above this fail immediately. 0 disables. */
    val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
    /**
     * Explicit parent context for telemetry produced by this logical request.
     * Dormant: carried for shape fidelity through the conversion paths, with
     * no consumer of its own. Presence boolean only in toString().
     */
    val telemetryContext: TelemetryContext? = null,
) {
    override fun toString(): String = optionsToString(
        "StreamOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "telemetryContext" to (telemetryContext != null),
    )

    companion object {
        const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
    }
}

/** Provider-neutral request options used by the models-level stream entry point. */
data class SimpleStreamOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoning: ThinkingLevel? = null,
    /**
     * Narrow tool choice: the full union lives only on completions-level
     * options; pi's `deferred` flag is not represented.
     */
    val toolChoice: SimpleToolChoice? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    /** Prompt-cache retention preference; null resolves from env/default. */
    val cacheRetention: CacheRetention? = null,
    /** Per-request provider env; credential values are merged in. */
    val env: Map<String, String> = emptyMap(),
    /** Explicit request headers; merged over resolved auth headers. */
    val headers: Map<String, String?> = emptyMap(),
    /** Per-level thinking token budgets; consumed by budget-based adapters. */
    val thinkingBudgets: Map<ThinkingLevel, Int> = emptyMap(),
    /**
     * Inspects or replaces the request payload before it is serialized and
     * sent; returning null keeps it unchanged. Receives full message content
     * — installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /**
     * Invoked after HTTP response headers are received and before the body
     * stream is consumed. Whether it fires for non-2xx responses is per
     * adapter. Never included in toString().
     */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Arbitrary sampling parameters merged into the request body as-is,
     * after the named request fields, so keys here override them. Lets
     * custom OpenAI-compatible servers (llama.cpp, vLLM, SGLang, ...)
     * receive parameters pi does not model. Applied only by
     * OpenAI-compatible adapters; other APIs ignore it. Only keys (never
     * values) may appear in toString().
     */
    val samplingParams: Map<String, JsonElement>? = null,
    /**
     * Transport selection for providers that support more than SSE. Only the
     * Codex adapter consumes it; other APIs ignore it. Null defaults to the
     * adapter's effective default (Codex: [Transport.AUTO]).
     */
    val transport: Transport? = null,
    /** WebSocket handshake timeout. Only the Codex adapter consumes it; other APIs ignore it. */
    val websocketConnectTimeoutMs: Long? = null,
    /**
     * Explicit parent context for telemetry produced by this logical request.
     * Dormant: carried for shape fidelity and preserved (same object) through
     * every conversion. Presence boolean only in toString().
     */
    val telemetryContext: TelemetryContext? = null,
) {
    override fun toString(): String = optionsToString(
        "SimpleStreamOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "reasoning" to reasoning,
        "toolChoice" to toolChoice,
        "cacheRetention" to cacheRetention,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "samplingParams" to samplingParams?.keys,
        "transport" to transport,
        "websocketConnectTimeoutMs" to websocketConnectTimeoutMs,
        "telemetryContext" to (telemetryContext != null),
    )

    fun toStreamOptions(reasoningEffort: ModelThinkingLevel?): OpenAiCompletionsOptions =
        OpenAiCompletionsOptions(
            apiKey = apiKey,
            sessionId = sessionId,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            toolChoice = toolChoice?.toToolChoice(),
            cacheRetention = cacheRetention,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            maxRetryDelayMs = maxRetryDelayMs,
            env = env,
            headers = headers,
            thinkingBudgets = thinkingBudgets,
            onPayload = onPayload,
            onResponse = onResponse,
            samplingParams = samplingParams,
            telemetryContext = telemetryContext,
        )
}

/**
 * Request-level keys override [Model.samplingParams] defaults per key; the
 * result is null when both are absent.
 *
 * Upstream has no such export — each adapter merges the maps inline in its
 * `buildParams`; the shared helper here is an accepted pathfinder
 * centralization (differences.md §4).
 */
fun mergeSamplingParams(model: Model, options: SimpleStreamOptions): Map<String, JsonElement>? =
    if (model.samplingParams.isNullOrEmpty() && options.samplingParams == null) {
        null
    } else {
        (model.samplingParams ?: emptyMap()) + (options.samplingParams ?: emptyMap())
    }

/**
 * Merges provider headers: [override] wins case-insensitively per header
 * name, and a null override value removes the header entirely.
 *
 * Upstream keeps this logic private per API file; the shared helper here is
 * an accepted pathfinder centralization (differences.md §4).
 */
fun mergeHeaders(
    base: Map<String, String?>,
    override: Map<String, String?>,
): Map<String, String?> {
    if (base.isEmpty() && override.isEmpty()) return emptyMap()
    val merged = LinkedHashMap(base)
    for ((name, value) in override) {
        val lowerName = name.lowercase()
        merged.keys.filter { it.lowercase() == lowerName }.forEach { merged.remove(it) }
        merged[name] = value
    }
    return merged
}

/**
 * True when [headers] sets a non-blank value for [name] (case-insensitive).
 *
 * Upstream keeps this logic private per API file; the shared helper here is
 * an accepted pathfinder centralization (differences.md §4).
 */
fun hasHeader(headers: Map<String, String?>, name: String): Boolean =
    headers.any { it.key.lowercase() == name && !it.value.isNullOrBlank() }

enum class InputModality { TEXT, IMAGE }

data class Model(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val reasoning: Boolean = false,
    val thinkingLevelMap: ThinkingLevelMap? = null,
    val input: List<InputModality> = listOf(InputModality.TEXT),
    val cost: ModelCost = ModelCost(),
    val contextWindow: Int = 4096,
    val maxTokens: Int = 4096,
    val compat: OpenAiCompletionsCompat = OpenAiCompletionsCompat(),
    val anthropicCompat: AnthropicMessagesCompat = AnthropicMessagesCompat(),
    val responsesCompat: OpenAiResponsesCompat? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * Default sampling parameters for this model, merged under per-request
     * [SimpleStreamOptions.samplingParams] keys by [mergeSamplingParams] and
     * applied only by OpenAI-compatible adapters. Not carried by the
     * generated model catalog.
     */
    val samplingParams: Map<String, JsonElement>? = null,
)

/** Per-million-token reference rates. */
interface ModelCostRates {
    val input: Double
    val output: Double
    val cacheRead: Double
    val cacheWrite: Double
}

data class ModelCost(
    override val input: Double = 0.0,
    override val output: Double = 0.0,
    override val cacheRead: Double = 0.0,
    override val cacheWrite: Double = 0.0,
    val tiers: List<ModelCostTier> = emptyList(),
) : ModelCostRates

/**
 * A rate set applied to the full request when total input usage exceeds
 * [inputTokensAbove].
 */
data class ModelCostTier(
    override val input: Double,
    override val output: Double,
    override val cacheRead: Double,
    override val cacheWrite: Double,
    val inputTokensAbove: Int,
) : ModelCostRates

/** How the provider expects the max output token limit to be spelled. */
enum class MaxTokensField { MAX_COMPLETION_TOKENS, MAX_TOKENS }

enum class ThinkingFormat { OPENAI, ZAI, QWEN, DEEPSEEK, BASETEN, OPENROUTER, ANT_LING, TOGETHER }

/** Only "anthropic" exists upstream; the single-member enum keeps pi's value domain. */
enum class CacheControlFormat { ANTHROPIC }

data class OpenAiCompletionsCompat(
    val supportsStore: Boolean = true,
    val supportsDeveloperRole: Boolean = true,
    val supportsReasoningEffort: Boolean = true,
    val supportsUsageInStreaming: Boolean = true,
    val supportsFinishReason: Boolean = true,
    val maxTokensField: MaxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
    val requiresToolResultName: Boolean = false,
    val requiresThinkingAsText: Boolean = false,
    val thinkingFormat: ThinkingFormat = ThinkingFormat.OPENAI,
    /** ZAI tool-call streaming flag (`tool_stream: true`). */
    val zaiToolStream: Boolean = false,
    /** Baseten chat_template_args: template kwargs before $var resolution. */
    val chatTemplateArgs: Map<String, ChatTemplateKwargValue> = emptyMap(),
    /** Whether to send session-affinity data from `options.sessionId`. */
    val sendSessionAffinityHeaders: Boolean = false,
    /** null means auto-detect (openrouter vs openai). */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    /** Whether `prompt_cache_retention: "24h"` is supported. */
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = true,
    val supportsOpenAIGrammarTools: Boolean = false,
    /** "anthropic" enables Anthropic-style cache_control emission; null disables it. */
    val cacheControlFormat: CacheControlFormat? = null,
    /**
     * For DeepSeek-style reasoning models, replayed assistant messages carry
     * `reasoning_content: ""` when no reasoning was set; some providers reject
     * assistant messages without it.
     */
    val requiresReasoningContentOnAssistantMessages: Boolean = false,
    /**
     * Provider-specific deferred tool serialization: "kimi" emits deferred
     * tools as a bare `tools` system message after tool results instead of the
     * standard `tools` param entry.
     */
    val deferredToolsMode: DeferredToolsMode? = null,
)

/** Only "kimi" exists upstream. */
enum class DeferredToolsMode { KIMI }

data class OpenAiResponsesCompat(
    val supportsDeveloperRole: Boolean = true,
    /** null means auto-detect from provider/baseUrl. */
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = false,
    val supportsOpenAIGrammarTools: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsExplicitPromptCacheMode: Boolean = false,
    /** Whether to send the `max_output_tokens` param. */
    val supportsMaxOutputTokens: Boolean = true,
)

data class AnthropicMessagesCompat(
    val supportsEagerToolInputStreaming: Boolean = true,
    val supportsLongCacheRetention: Boolean = true,
    val sendSessionAffinityHeaders: Boolean = false,
    val supportsCacheControlOnTools: Boolean = true,
    val supportsTemperature: Boolean = true,
    val allowEmptySignature: Boolean = false,
    val supportsStrictTools: Boolean = false,
    /** null = unset, true/false explicit. */
    val forceAdaptiveThinking: Boolean? = null,
    /**
     * Models Anthropic accepts in `fallbacks` for server-side refusal fallback,
     * with local pricing metadata for returned fallback responses. When empty,
     * callers must omit `fallbacks`; Anthropic rejects the field for models
     * with no permitted fallback targets.
     */
    val allowedFallbackModels: List<AnthropicAllowedFallbackModel> = emptyList(),
)

fun anthropicCompatOf(model: Model): AnthropicMessagesCompat = model.anthropicCompat

/**
 * One server-side fallback target. Only [model] goes on the wire in
 * `fallbacks`; [provider] and [cost] are local metadata for usage attribution
 * when the server serves the fallback model.
 */
data class AnthropicAllowedFallbackModel(
    val provider: String,
    val model: String,
    val cost: ModelCost,
)

/**
 * Maps thinking levels to provider-specific reasoning-effort strings with
 * three-state semantics: a key present with a non-null string maps the
 * level to that effort; a key present with `null` marks the level
 * explicitly unsupported; a missing key means unspecified
 * (default-supported).
 */
class ThinkingLevelMap private constructor(private val levels: Map<ModelThinkingLevel, String?>) {

    fun isSpecified(level: ModelThinkingLevel): Boolean = levels.containsKey(level)

    fun forLevel(level: ModelThinkingLevel): String? = levels[level]

    override fun equals(other: Any?): Boolean = other is ThinkingLevelMap && other.levels == levels
    override fun hashCode(): Int = levels.hashCode()
    override fun toString(): String = "ThinkingLevelMap($levels)"

    companion object {
        fun of(vararg pairs: Pair<ModelThinkingLevel, String?>): ThinkingLevelMap =
            ThinkingLevelMap(linkedMapOf(*pairs))
    }
}

/** A chat-template kwarg value: a plain JSON scalar or a `$var` reference resolved at request time. */
sealed interface ChatTemplateKwargValue {
    data class Scalar(val value: JsonElement) : ChatTemplateKwargValue

    /** `$var` reference. [varName] is e.g. "thinking.enabled". */
    data class Ref(
        val varName: String,
        val omitWhenOff: Boolean = false,
    ) : ChatTemplateKwargValue

    companion object {
        fun of(value: String): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun of(value: Double): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun of(value: Boolean): ChatTemplateKwargValue = Scalar(JsonPrimitive(value))
        fun ofNull(): ChatTemplateKwargValue = Scalar(JsonNull)
    }
}

enum class SessionAffinityFormat { OPENAI, OPENAI_NOSESSION, OPENROUTER }

/**
 * The uniform stream contract every API adapter module implements (pi
 * `ProviderStreams.streamSimple`).
 *
 * Implementations stream assistant message events and encode failures in
 * the stream itself rather than throwing.
 */
interface ChatApi {
    fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): Flow<AssistantMessageEvent>
}

/**
 * The exceptions below are pathfinder-side encodings of upstream behavior
 * (pi throws plain errors inline in its adapters and exports no exception
 * types); they live next to the [ChatApi] contract they serve.
 */

/** Thrown internally by streaming implementations; surfaced as an error event. */
class ProviderStreamException(
    message: String,
    val stopReason: StopReason = StopReason.ERROR,
) : Exception(message)

class ProviderAuthException(message: String) : Exception(message)

/**
 * Control-flow sentinel thrown to unwind SSE collection once a terminal
 * chunk has been processed (upstream streams simply end; the DOM-based
 * collectors need an explicit unwind). Shared — adapters must not redeclare
 * private copies.
 */
internal class DoneSentinel : RuntimeException()
