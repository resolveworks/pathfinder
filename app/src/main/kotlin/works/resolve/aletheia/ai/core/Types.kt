package works.resolve.aletheia.ai.core

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

/** Thinking level including the "off" state. */
enum class ModelThinkingLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

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
) : Content() {
    override val type: ContentType get() = ContentType.TOOL_CALL
}

data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
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

enum class StopReason { PENDING, STOP, LENGTH, TOOL_USE, ERROR, ABORTED }

sealed class Message {
    abstract val role: MessageRole
    abstract val timestamp: Long
}

enum class MessageRole { USER, ASSISTANT, TOOL_RESULT }

data class UserMessage(
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
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.ASSISTANT
}

data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<Content>,
    val isError: Boolean = false,
    override val timestamp: Long = 0L,
) : Message() {
    override val role: MessageRole get() = MessageRole.TOOL_RESULT
}

/** Tool definition; parameters is a JSON Schema object. */
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<Tool> = emptyList(),
)
