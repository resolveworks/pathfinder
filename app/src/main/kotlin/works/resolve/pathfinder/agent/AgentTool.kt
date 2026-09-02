package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How tool calls from a single assistant message are executed.
 *
 * - [SEQUENTIAL]: each tool call is prepared, executed, and finalized before
 *   the next one starts.
 * - [PARALLEL]: calls are prepared sequentially, then allowed tools execute
 *   concurrently. `tool_execution_end` is emitted in tool completion order
 *   after each tool is finalized, while tool-result message artifacts are
 *   emitted later in assistant source order.
 */
enum class ToolExecutionMode { SEQUENTIAL, PARALLEL }

/**
 * Final or partial result produced by a tool. Divergence: pi's `terminate`
 * field is omitted — upstream it only participates in the hook
 * early-termination rule, and hooks are out of scope.
 */
data class AgentToolResult(
    /** Text or image content returned to the model. */
    val content: List<Content>,
    /** Arbitrary structured details for logs or UI rendering. */
    val details: JsonElement? = null,
    /** Usage of the final tool execution itself; not used for main LLM context accounting. */
    val usage: Usage? = null,
    /** Tools introduced by this result, available from this transcript point onward. */
    val addedToolNames: List<String> = emptyList(),
) {
    init {
        require(content.all { it is TextContent || it is ImageContent }) {
            "AgentToolResult.content may contain only TextContent or ImageContent"
        }
    }
}

/**
 * Callback tools use to stream partial execution updates. Scoped to the
 * current [AgentTool.execute] invocation; calls made after the tool settles
 * are ignored, and the loop owns serialization and lifetime of update events.
 */
typealias AgentToolUpdateCallback = (AgentToolResult) -> Unit

interface AgentTool {
    val definition: Tool

    val label: String

    /** Per-tool override of the run's execution mode; null uses the default. */
    val executionMode: ToolExecutionMode? get() = null

    /**
     * Optional one-line snippet for the Available tools section of the default
     * system prompt; tools without one are omitted from that section.
     *
     * Placement divergence: pi keeps this on its coding-agent `ToolDefinition`,
     * but pathfinder has no separate coding-agent tool layer, so [AgentTool]
     * hosts it.
     */
    val promptSnippet: String? get() = null

    /**
     * Guideline bullets appended to the default system prompt Guidelines
     * section while this tool is active; see [promptSnippet] for placement.
     */
    val promptGuidelines: List<String> get() = emptyList()

    /**
     * Validates raw parsed tool-call arguments against [definition.parameters]
     * and may return a normalized copy. Instead of pi's TypeBox/JSON-Schema
     * validation, each tool owns typed decoding/validation and throws on
     * failure; the loop catches that and converts it to an error tool result.
     */
    fun validateArguments(arguments: JsonObject): JsonObject

    /**
     * Execute the tool call, throwing on failure instead of encoding errors
     * in [AgentToolResult.content]. Coroutine cancellation replaces pi's
     * `AbortSignal`; [onUpdate] is required — a non-streaming tool passes a
     * no-op.
     */
    suspend fun execute(toolCallId: String, arguments: JsonObject, onUpdate: AgentToolUpdateCallback): AgentToolResult
}

/**
 * Context snapshot passed into the low-level agent loop. Divergence: pi's
 * `systemPrompt` is required; this port keeps it nullable to match
 * [works.resolve.pathfinder.ai.core.Context.systemPrompt].
 */
data class AgentContext(
    val systemPrompt: String? = null,
    val messages: List<Message> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
)
