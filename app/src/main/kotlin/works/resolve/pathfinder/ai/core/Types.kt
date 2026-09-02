package works.resolve.pathfinder.ai.core

import kotlinx.serialization.json.JsonElement

enum class InputModality { TEXT, IMAGE }

enum class ThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

enum class CacheRetention { SHORT, LONG, NONE }

/**
 * Only the Codex adapter consumes the transport choice; other APIs ignore
 * it. AUTO is WebSocket-first with per-session SSE fallback.
 */
enum class Transport { SSE, WEBSOCKET, WEBSOCKET_CACHED, AUTO }

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

/**
 * No provider adapter produces DEFERRED; it exists because the session
 * layer drops deferred assistant messages from context.
 */
enum class StopReason { PENDING, STOP, LENGTH, TOOL_USE, ERROR, ABORTED, DEFERRED }

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
