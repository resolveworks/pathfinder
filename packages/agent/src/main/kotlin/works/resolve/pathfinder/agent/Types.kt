package works.resolve.pathfinder.agent

import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage

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

data class AgentLoopConfig(
    val model: Model,
    val options: SimpleStreamOptions = SimpleStreamOptions(),
    val streamFn: StreamFn,
    /**
     * Batch execution mode; a per-tool `executionMode = SEQUENTIAL` override
     * forces the whole batch sequential.
     */
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val clock: Clock = Clock.System
)

/**
 * Immutable public state of an [Agent], exposed as a [StateFlow].
 * [streamingMessage] is the partial message currently being streamed — of any
 * role, since user and tool-result message starts transiently occupy it too —
 * and [pendingToolCalls] the ids of tool calls whose execution has started
 * but not ended.
 */
data class AgentState(
    val model: Model,
    val messages: List<Message> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
    val systemPrompt: String? = null,
    val streamingMessage: Message? = null,
    val pendingToolCalls: Set<String> = emptySet(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val thinkingLevel: ModelThinkingLevel = ModelThinkingLevel.OFF
)

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
    val addedToolNames: List<String> = emptyList()
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
    suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: AgentToolUpdateCallback
    ): AgentToolResult
}

/**
 * Context snapshot passed into the low-level agent loop. Divergence: pi's
 * `systemPrompt` is required; this port keeps it nullable to match
 * [works.resolve.pathfinder.ai.Context.systemPrompt].
 */
data class AgentContext(
    val systemPrompt: String? = null,
    val messages: List<Message> = emptyList(),
    val tools: List<AgentTool> = emptyList()
)

/** Lifecycle events emitted by the agent loop. */
sealed class AgentEvent {
    object AgentStart : AgentEvent()

    /** Terminal event carrying every message produced by this run, in source order. */
    data class AgentEnd(val messages: List<Message>) : AgentEvent()

    object TurnStart : AgentEvent()

    /** Final assistant message of the turn plus its tool results in source order. */
    data class TurnEnd(
        val message: AssistantMessage,
        val toolResults: List<ToolResultMessage> = emptyList()
    ) : AgentEvent()

    data class MessageStart(val message: Message) : AgentEvent()

    /** Only emitted for assistant messages while streaming. */
    data class MessageUpdate(
        val message: AssistantMessage,
        val assistantMessageEvent: AssistantMessageEvent
    ) : AgentEvent()

    data class MessageEnd(val message: Message) : AgentEvent()

    /**
     * [arguments] is the raw parsed JSON of the assistant call; validated
     * arguments are used only for execution and in [ToolExecutionUpdate].
     */
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val arguments: JsonObject
    ) : AgentEvent()

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val arguments: JsonObject,
        val partialResult: AgentToolResult
    ) : AgentEvent()

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean
    ) : AgentEvent()

    /**
     * A retryable run is being retried after an exponential-backoff delay.
     * Emitted by the [Agent] facade; never appears in [runAgentLoop] output.
     */
    data class AutoRetryStart(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String
    ) : AgentEvent()

    /**
     * The retry sequence ended: a retried run succeeded, the budget was
     * exhausted, or the backoff was cancelled.
     */
    data class AutoRetryEnd(
        val success: Boolean,
        val attempt: Int,
        val finalError: String? = null
    ) : AgentEvent()

    /**
     * Trigger of a compaction run. [CompactionReason.MANUAL] is carried for
     * event-shape fidelity only; this port never emits it.
     */
    enum class CompactionReason { MANUAL, THRESHOLD, OVERFLOW }

    sealed interface SummarizationSource {
        data object BranchSummary : SummarizationSource

        data class Compaction(val reason: CompactionReason) : SummarizationSource
    }

    /**
     * Payload of [AgentEvent.CompactionEnd]. Pi's `firstKeptEntryId` is not
     * ported: [works.resolve.pathfinder.codingagent.core.session.CompactionEntry] stores
     * the retained tail directly instead of a kept-entry pointer.
     */
    data class CompactionResult(
        val summary: String,
        val tokensBefore: Int,
        val estimatedTokensAfter: Int,
        val usage: Usage?,
        val details: works.resolve.pathfinder.agent.CompactionDetails?
    )

    /** Automatic compaction started; emitted by [AgentSession] between agent runs. */
    data class CompactionStart(val reason: CompactionReason) : AgentEvent()

    /** Compaction ended — succeeded, was aborted, or failed. */
    data class CompactionEnd(
        val reason: CompactionReason,
        val result: CompactionResult? = null,
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String? = null
    ) : AgentEvent()

    /** A summarization retry is scheduled: the summary LLM call is backed off. */
    data class SummarizationRetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String
    ) : AgentEvent()

    data class SummarizationRetryAttemptStart(val source: SummarizationSource) : AgentEvent()

    object SummarizationRetryFinished : AgentEvent()
}
