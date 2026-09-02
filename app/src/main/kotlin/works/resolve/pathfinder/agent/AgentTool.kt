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
 * Configuration for how tool calls from a single assistant message are
 * executed. Ported from pi's ToolExecutionMode (packages/agent/src/types.ts):
 *
 * - [SEQUENTIAL]: each tool call is prepared, executed, and finalized before
 *   the next one starts.
 * - [PARALLEL]: tool calls are prepared sequentially, then allowed tools
 *   execute concurrently. `tool_execution_end` is emitted in tool completion
 *   order after each tool is finalized, while tool-result message artifacts
 *   are emitted later in assistant source order.
 */
enum class ToolExecutionMode { SEQUENTIAL, PARALLEL }

/**
 * Final or partial result produced by a tool. Ported from pi's AgentToolResult
 * (packages/agent/src/types.ts).
 *
 * Divergence: pi's `terminate` field is omitted. Upstream it only participates
 * in the `beforeToolCall`/`afterToolCall` hook early-termination rule, and
 * hooks are out of scope for this port.
 *
 * @throws IllegalArgumentException if [content] contains anything other than
 *   [TextContent] or [ImageContent] (pi: `(TextContent | ImageContent)[]`).
 */
data class AgentToolResult(
    /** Text or image content returned to the model. */
    val content: List<Content>,
    /** Arbitrary structured details for logs or UI rendering. */
    val details: JsonElement? = null,
    /** Usage from the final tool execution itself, if available. Not used for main LLM context accounting. */
    val usage: Usage? = null,
    /** Names of tools introduced by this result and available from this transcript point onward. */
    val addedToolNames: List<String> = emptyList(),
) {
    init {
        require(content.all { it is TextContent || it is ImageContent }) {
            "AgentToolResult.content may contain only TextContent or ImageContent"
        }
    }
}

/**
 * Callback used by tools to stream partial execution updates. Ported from pi's
 * AgentToolUpdateCallback (packages/agent/src/types.ts), a plain non-suspending
 * function.
 *
 * Pi semantics: the callback is scoped to the current [AgentTool.execute]
 * invocation; calls made after the tool settles are ignored; the loop (not the
 * tool) owns serialization and lifetime of update events.
 */
typealias AgentToolUpdateCallback = (AgentToolResult) -> Unit

/** Tool definition used by the agent runtime. Ported from pi's AgentTool (packages/agent/src/types.ts). */
interface AgentTool {
    /** The single provider-facing tool definition (name/description/parameters). */
    val definition: Tool

    /** Human-readable label for UI display (pi's AgentTool.label). */
    val label: String

    /**
     * Per-tool execution mode override (pi's AgentTool.executionMode):
     * [ToolExecutionMode.SEQUENTIAL] means this tool must execute one at a
     * time with other tool calls; [ToolExecutionMode.PARALLEL] means it can
     * execute concurrently with others. If null, the default execution mode
     * applies.
     */
    val executionMode: ToolExecutionMode? get() = null

    /**
     * Optional one-line snippet for the Available tools section in the default
     * system prompt. Custom tools are omitted from that section when this is
     * not provided.
     *
     * Ports `ToolDefinition.promptSnippet`
     * (packages/coding-agent/src/core/extensions/types.ts:461). Placement
     * divergence: pi keeps this on its coding-agent `ToolDefinition` rather
     * than `packages/agent`'s `AgentTool`, but pathfinder has no separate
     * coding-agent tool layer (no extension runner, no `ToolDefinition`), so
     * [AgentTool] — already carrying pi's `label`/`executionMode` — is the
     * `ToolDefinition` analog and hosts this field verbatim.
     */
    val promptSnippet: String? get() = null

    /**
     * Optional guideline bullets appended to the default system prompt
     * Guidelines section when this tool is active.
     *
     * Ports `ToolDefinition.promptGuidelines`
     * (packages/coding-agent/src/core/extensions/types.ts:463); see
     * [promptSnippet] for the placement decision.
     */
    val promptGuidelines: List<String> get() = emptyList()

    /**
     * Validates raw parsed tool-call arguments against this tool's
     * [definition.parameters] and may return a normalized copy.
     *
     * Kotlin adaptation of pi's `prepareArguments` + `validateToolArguments`
     * (packages/agent/src/types.ts AgentTool.prepareArguments;
     * packages/ai/src/utils/validation.ts:317-350): instead of a
     * TypeBox/JSON-Schema validation framework, each tool owns typed
     * decoding/validation and throws (e.g. [IllegalArgumentException]) on
     * failure — the loop catches failures and converts them to error tool
     * results, mirroring pi's thrown `Error` from `validateToolArguments`.
     */
    fun validateArguments(arguments: JsonObject): JsonObject

    /**
     * Execute the tool call. Throw on failure instead of encoding errors in
     * [AgentToolResult.content] (pi's AgentTool.execute contract).
     *
     * Kotlin divergences from pi's
     * `execute(toolCallId, params, signal?, onUpdate?)`:
     * - coroutine cancellation replaces pi's `AbortSignal`, so there is no
     *   signal parameter;
     * - [onUpdate] is required — a non-streaming tool passes a no-op.
     */
    suspend fun execute(toolCallId: String, arguments: JsonObject, onUpdate: AgentToolUpdateCallback): AgentToolResult
}

/**
 * Context snapshot passed into the low-level agent loop. Ported from pi's
 * AgentContext (packages/agent/src/types.ts): system prompt, transcript
 * visible to the model, tools available for this run.
 *
 * Divergence: upstream `systemPrompt` is a required `string`, while
 * [works.resolve.pathfinder.ai.core.Context.systemPrompt] is nullable, so this
 * port keeps [String?].
 */
data class AgentContext(
    val systemPrompt: String? = null,
    val messages: List<Message> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
)
