package works.resolve.pathfinder.ai.core

import kotlinx.serialization.json.JsonElement

/**
 * Core chat content, message, and model metadata types, ported from the pi
 * project's ai package (packages/ai/src/types.ts), reduced to what the
 * OpenAI Chat Completions API family needs.
 */

/** Modality a model accepts as input. */
enum class InputModality { TEXT, IMAGE }

/** Reasoning effort levels, mirroring pi's ThinkingLevel. */
enum class ThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/** Prompt-cache retention preference, pi's CacheRetention. */
enum class CacheRetention { SHORT, LONG, NONE }

/**
 * pi's `Transport` union (packages/ai/src/types.ts:110):
 * `"sse" | "websocket" | "websocket-cached" | "auto"`. Only the Codex
 * adapter consumes it (pi types.ts:200-202: other APIs ignore it);
 * `AUTO` is WebSocket-first with per-session SSE fallback.
 */
enum class Transport { SSE, WEBSOCKET, WEBSOCKET_CACHED, AUTO }

/** Thinking level including the "off" state. */
enum class ModelThinkingLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/**
 * pi models these levels as the union `"off" | ThinkingLevel`
 * (types.ts:83-85): every ThinkingLevel names exactly one
 * ModelThinkingLevel, so mapping up is total. Explicit `when` (not
 * `valueOf(name)`) so a new upstream level forces an update here instead of
 * failing at runtime.
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
 * The down direction of pi's `"off" | ThinkingLevel` union: OFF has no
 * ThinkingLevel, so it maps to null and callers decide what "off" means at
 * their boundary.
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

/**
 * Maps pi thinking levels to provider-specific reasoning-effort strings,
 * mirroring pi's `ThinkingLevelMap`: a key present with a non-null string maps
 * the level to that effort; a key present with `null` marks the level
 * explicitly unsupported; a missing key means unspecified (pass the level
 * through / default-supported, per pi's `undefined` semantics).
 */
class ThinkingLevelMap private constructor(private val levels: Map<ModelThinkingLevel, String?>) {

    /** True when the level has an explicit entry (even if null). */
    fun isSpecified(level: ModelThinkingLevel): Boolean = levels.containsKey(level)

    /** The mapped effort string; null when unsupported or unspecified. */
    fun forLevel(level: ModelThinkingLevel): String? = levels[level]

    override fun equals(other: Any?): Boolean = other is ThinkingLevelMap && other.levels == levels
    override fun hashCode(): Int = levels.hashCode()
    override fun toString(): String = "ThinkingLevelMap($levels)"

    companion object {
        fun of(vararg pairs: Pair<ModelThinkingLevel, String?>): ThinkingLevelMap =
            ThinkingLevelMap(linkedMapOf(*pairs))
    }
}

sealed class Content {
    abstract val type: ContentType
}

enum class ContentType { TEXT, THINKING, IMAGE, TOOL_CALL }

data class TextContent(
    val text: String,
    /**
     * Opaque thought-signature replay data Google attaches to a text part
     * (google-shared.ts `textSignature`); only meaningful for the same
     * provider/model.
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
     * part (google-shared.ts `thoughtSignature`); only meaningful for the
     * same provider/model.
     */
    val thoughtSignature: String? = null,
    /** OpenAI Responses namespace-scoped tool name (pi's ToolCall.namespace). */
    val namespace: String? = null,
) : Content() {
    override val type: ContentType get() = ContentType.TOOL_CALL
}

data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    /**
     * Subset of `cacheWrite` written with 1h retention; only Anthropic
     * reports this split (pi types.ts Usage.cacheWrite1h). Non-Anthropic
     * adapters leave it at 0, matching pi's undefined.
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

/**
 * StopReason, pi's stop reasons (types.ts StopReason). DEFERRED is ported
 * for session-parity consumers (pi's sessionEntryToContextMessages drops
 * deferred assistant messages from context, harness/session/context.ts:72;
 * SessionStopReason in harness/session/types.ts:10 adds "deferred"); no
 * pathfinder adapter produces it — deferred responses stay excluded per the
 * adapter-capability scope in ai/AGENTS.md.
 */
enum class StopReason { PENDING, STOP, LENGTH, TOOL_USE, ERROR, ABORTED, DEFERRED }

sealed class Message {
    abstract val role: MessageRole
    abstract val timestamp: Long
}

enum class MessageRole { USER, ASSISTANT, TOOL_RESULT }

data class UserMessage(
    // Reduction: pi's UserMessage.content is `string | (TextContent | ImageContent)[]`
    // (types.ts:426); the port accepts only the structured array form — use
    // [ofText] for the plain-string shape.
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
    // Reduction: pi's AssistantMessage.diagnostics (types.ts:435,
    // AssistantMessageDiagnostic) is not ported; no adapter in scope emits it.
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
     * pi's `details?: TDetails` (types.ts:452): arbitrary structured runtime/UI
     * metadata attached to the result. Preserved verbatim; no provider adapter
     * reads it (runtime/UI data, not a wire field).
     */
    val details: JsonElement? = null,
    /**
     * pi's `usage?: Usage` (types.ts:454): usage from the tool execution
     * itself, if available. Not part of main LLM context accounting.
     * No provider adapter reads it.
     */
    val usage: Usage? = null,
    val isError: Boolean = false,
    /** Tool names this result made available (pi's addedToolNames, deferred tool loading). */
    val addedToolNames: List<String> = emptyList(),
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.TOOL_RESULT
}

/**
 * OpenAI grammar variants for constrained sampling (types.ts:494).
 */
enum class GrammarFormat { OPENAI_LARK, OPENAI_REGEX }

/** pi's `GrammarVariants = Partial<Record<GrammarFormat, string>>` (types.ts:496). */
typealias GrammarVariants = Map<GrammarFormat, String>

/** pi's `strict: "prefer" | "require"` union (types.ts:505). */
enum class StrictJsonSchemaMode { PREFER, REQUIRE }

/**
 * Optional provider-side constrained sampling configs for a tool
 * (types.ts:499-512).
 *
 * The `json_schema` value roughly maps to the concept of `strict` in APIs
 * which is implemented as json-schema constrained sampling by APIs. Grammar
 * variants let callers provide provider-specific encodings of the same
 * intended language.
 *
 * Divergence from pi: upstream `Tool.constrainedSampling?: false |
 * ConstrainedSamplingConfig` models three states (unset, explicit `false`,
 * config) in one union; Kotlin models unset as `null` on
 * [Tool.constrainedSampling] and pi's `false` as [Disabled], with the config
 * variants as the remaining subclasses.
 */
sealed interface ConstrainedSamplingConfig {
    /** pi's explicit `constrainedSampling: false` disable value. */
    data object Disabled : ConstrainedSamplingConfig

    /** pi's `{type: "json_schema", strict: "prefer" | "require"}`. */
    data class JsonSchema(val strict: StrictJsonSchemaMode) : ConstrainedSamplingConfig

    /** pi's `{type: "grammar", variants}` with [GrammarVariants]. */
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
 * Narrow tool-selection union for pi's simple API: pi's `type ToolChoice =
 * "auto" | "none"` (types.ts:82), used by StreamOptions/SimpleStreamOptions
 * (types.ts:316). The full SDK-level union lives only on the
 * OpenAI-completions options (api/openai-completions.ts:164).
 */
sealed interface SimpleToolChoice {
    data object Auto : SimpleToolChoice
    data object None : SimpleToolChoice
}

/** Maps the narrow simple-API choice onto the full completions-level union. */
fun SimpleToolChoice.toToolChoice(): ToolChoice = when (this) {
    SimpleToolChoice.Auto -> ToolChoice.Auto
    SimpleToolChoice.None -> ToolChoice.None
}

/**
 * Full tool-selection union, pi's OpenAICompletionsOptions.toolChoice
 * (api/openai-completions.ts:164, OpenAI's ChatCompletionToolChoiceOption:
 * "auto" | "none" | "required" | {type:"function"...}). Also carried by the
 * port's provider-specific options types whose pi counterparts define their
 * own broader unions. The simple API must not accept these values; it uses
 * [SimpleToolChoice] (types.ts:82).
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
