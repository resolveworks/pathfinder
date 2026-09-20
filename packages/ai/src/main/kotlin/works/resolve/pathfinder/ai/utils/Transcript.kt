package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolReference
import works.resolve.pathfinder.ai.TranscriptContext

/*
 * Pathfinder twin of pi's `utils/transcript.ts`: the replay and normalization
 * helpers shared by every provider adapter.
 */

/**
 * Build the leading system message for a prompt and tool set. Returns null when
 * both are empty, so an empty transcript stays empty.
 */
fun createInitialSystemMessage(systemPrompt: String?, tools: List<Tool>?): SystemMessage? {
    val hasSystemPrompt = systemPrompt != null && systemPrompt.isNotEmpty()
    val hasTools = tools != null && tools.isNotEmpty()
    if (!hasSystemPrompt && !hasTools) return null
    return SystemMessage(
        content = if (systemPrompt == null) emptyList() else listOf(TextContent(systemPrompt)),
        toolsAdded = if (hasTools) tools else null,
        timestamp = 0L
    )
}

/**
 * Fold `Context.systemPrompt` and `Context.tools` into a leading system message.
 * This is the only entry point that produces a [TranscriptContext]; every
 * provider-facing function expects the result.
 */
fun normalizeContext(context: Context): TranscriptContext {
    val initialMessage = createInitialSystemMessage(context.systemPrompt, context.tools)
    val messages = if (initialMessage != null) {
        listOf(initialMessage) + context.messages
    } else {
        context.messages
    }
    return TranscriptContext(messages)
}

/** Return the leading system message, if the transcript starts with one. */
fun getInitialSystemMessage(messages: List<Message>): SystemMessage? =
    messages.firstOrNull() as? SystemMessage

/** Drop the leading system message for APIs that carry the prompt outside the message list. */
fun withoutInitialSystemMessage(messages: List<Message>): List<Message> =
    if (getInitialSystemMessage(messages) != null) messages.drop(1) else messages

/** Resolve the tools available after applying every transcript delta in order. */
fun getCurrentTools(messages: List<Message>): List<Tool> {
    val tools = LinkedHashMap<String, Tool>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        for (tool in message.toolsRemoved.orEmpty()) tools.remove(tool.name)
        for (tool in message.toolsAdded.orEmpty()) tools[tool.name] = tool
    }
    return tools.values.toList()
}

/**
 * Replay every system message into one leading system message holding the current
 * prompt and tools. Later `content` is appended to the base prompt, `sections` are
 * patched by name, and tools are resolved with [getCurrentTools].
 */
fun getCurrentSystemMessage(messages: List<Message>): SystemMessage? {
    val content = mutableListOf<String>()
    val sections = LinkedHashMap<String, String>()
    var timestamp: Long? = null
    for (message in messages) {
        if (message !is SystemMessage) continue
        if (timestamp == null) timestamp = message.timestamp
        val text = contentText(message.content)
        if (text.isNotEmpty()) content.add(text)
        for ((name, value) in message.sections.orEmpty()) {
            if (value == null) sections.remove(name) else sections[name] = value
        }
    }
    val tools = getCurrentTools(messages)
    if (timestamp == null && tools.isEmpty()) return null
    return SystemMessage(
        content = if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(TextContent(content.joinToString("\n\n")))
        },
        sections = if (sections.isNotEmpty()) sections else null,
        toolsAdded = if (tools.isNotEmpty()) tools else null,
        timestamp = timestamp ?: 0L
    )
}

/** Render the current system prompt text after replaying every system message. */
fun getCurrentSystemPrompt(messages: List<Message>): String {
    val message = getCurrentSystemMessage(messages)
    return if (message != null) getSystemMessageText(message) else ""
}

/**
 * Rebuild the transcript for APIs without mid-conversation system messages: the replayed
 * system message leads, and every later system message is dropped.
 */
fun collapseSystemMessages(context: TranscriptContext): TranscriptContext {
    val head = getCurrentSystemMessage(context.messages)
    val messages = context.messages.filter { it.role != MessageRole.SYSTEM }
    return TranscriptContext(if (head != null) listOf(head) + messages else messages)
}

/** Keep later system messages in place when the model accepts them; otherwise collapse them. */
fun resolveTranscript(
    context: TranscriptContext,
    supportsMidConvoSystemMessages: Boolean?
): TranscriptContext = if (supportsMidConvoSystemMessages == true) {
    context
} else {
    collapseSystemMessages(context)
}

/**
 * Strip executable and display-only fields from a tool before transcript comparison or
 * persistence.
 */
fun toToolDeclaration(tool: Tool): Tool = Tool(
    name = tool.name,
    description = tool.description,
    parameters = lenientJson.parseToJsonElement(
        lenientJson.encodeToString(JsonElement.serializer(), tool.parameters)
    ),
    constrainedSampling = tool.constrainedSampling
)

/**
 * Whether two tools declare the same interface to the model.
 *
 * Both sides go through [toToolDeclaration] first: the JSON round-trip drops the
 * typebox symbol keys and absent fields a structural comparison would see, and builds
 * both objects with the same key order, so comparing the serialized declarations is
 * exact. This avoids deep-equality over provider-shaped schemas.
 */
fun declarationsEqual(left: Tool, right: Tool): Boolean =
    lenientJson.encodeToString(JsonElement.serializer(), toolToJson(toToolDeclaration(left))) ==
        lenientJson.encodeToString(JsonElement.serializer(), toolToJson(toToolDeclaration(right)))

data class ToolStateChanges(val toolsAdded: List<Tool>, val toolsRemoved: List<ToolReference>)

/** Compare two complete tool states. A changed definition is a removal followed by an addition. */
fun getToolStateChanges(previous: List<Tool>, current: List<Tool>): ToolStateChanges {
    val previousTools = previous.associateBy { it.name }
    val currentTools = current.associateBy { it.name }
    return ToolStateChanges(
        toolsAdded = current.filter { tool ->
            val previousTool = previousTools[tool.name]
            previousTool == null || !declarationsEqual(previousTool, tool)
        }.map(::toToolDeclaration),
        toolsRemoved = previous.filter { tool ->
            val currentTool = currentTools[tool.name]
            currentTool == null || !declarationsEqual(tool, currentTool)
        }.map { ToolReference(it.name) }
    )
}

/** Every definition referenced by transcript tool state, in first-declaration order. */
fun getDeclaredTools(messages: List<Message>): List<Tool> {
    val definitions = LinkedHashMap<String, Tool>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        for (tool in message.toolsAdded.orEmpty()) definitions[tool.name] = tool
    }
    return definitions.values.toList()
}

/**
 * Whether a tool name was declared twice with different definitions. Transports that
 * reference tools by name (Anthropic `tool_addition`/`tool_removal`) cannot express that.
 */
fun hasToolRedefinitions(messages: List<Message>): Boolean {
    val declared = LinkedHashMap<String, Tool>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        for (tool in message.toolsAdded.orEmpty()) {
            val previous = declared[tool.name]
            if (previous != null && !declarationsEqual(previous, tool)) return true
            declared[tool.name] = tool
        }
    }
    return false
}

/**
 * Whether tool history contains a removal or same-name redeclaration that an addition-only
 * transport cannot replay.
 */
fun hasNonAdditiveToolChanges(messages: List<Message>): Boolean {
    val declared = mutableSetOf<String>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        if (!message.toolsRemoved.isNullOrEmpty()) return true
        for (tool in message.toolsAdded.orEmpty()) {
            if (tool.name in declared) return true
            declared.add(tool.name)
        }
    }
    return false
}

data class TranscriptTools(
    /** Tools sent in the top-level request field. */
    val requestTools: List<Tool>,
    /**
     * Whether later system messages carry their own `toolsAdded` as in-place additions.
     * When false, [requestTools] already holds the complete current tool set.
     */
    val anchorsAdditions: Boolean
)

/**
 * Split tool declarations between the top-level request field and in-place additions.
 * Transports that can anchor additions at a system message keep the initial tools at the
 * top and load later ones where they appear; that only works when no tool was removed or
 * redeclared, so everything else sends the current tool list.
 */
fun resolveTranscriptTools(
    messages: List<Message>,
    supportsToolAdditions: Boolean
): TranscriptTools {
    val anchorsAdditions = supportsToolAdditions && !hasNonAdditiveToolChanges(messages)
    return TranscriptTools(
        requestTools = if (anchorsAdditions) {
            getInitialSystemMessage(messages)?.toolsAdded.orEmpty()
        } else {
            getCurrentTools(messages)
        },
        anchorsAdditions = anchorsAdditions
    )
}

/**
 * pi's `Tool` wire shape: JSON.stringify includes name, description,
 * parameters, and constrainedSampling only when defined. Shared with token
 * estimation so both compare and measure the same serialization.
 */
internal fun toolToJson(tool: Tool): JsonObject = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("parameters", tool.parameters)
    tool.constrainedSampling?.let { put("constrainedSampling", constrainedSamplingToJson(it)) }
}

/** pi's `JSON.stringify([{name}])` shape for a tool-removal reference. */
internal fun toolReferenceToJson(reference: ToolReference): JsonObject =
    buildJsonObject { put("name", reference.name) }

/** The wire shape of `Tool.constrainedSampling`; shared by persistence so both write it identically. */
fun constrainedSamplingToJson(config: ConstrainedSamplingConfig): JsonElement = when (config) {
    ConstrainedSamplingConfig.Disabled -> JsonPrimitive(false)

    is ConstrainedSamplingConfig.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("strict", if (config.strict == StrictJsonSchemaMode.PREFER) "prefer" else "require")
    }

    is ConstrainedSamplingConfig.Grammar -> buildJsonObject {
        put("type", "grammar")
        put(
            "variants",
            buildJsonObject {
                config.variants.forEach { (format, definition) ->
                    put(
                        if (format ==
                            GrammarFormat.OPENAI_LARK
                        ) {
                            "openai_lark"
                        } else {
                            "openai_regex"
                        },
                        definition
                    )
                }
            }
        )
    }
}
