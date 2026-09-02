package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolChoice
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request construction for the native Mistral Chat Completions API.
 *
 * Divergence from pi: the wire payload is built directly in snake_case; pi
 * builds an internal camelCase payload and remaps it in `toMistralWirePayload`
 * so its onPayload hook can add SDK-style fields. This port builds the wire
 * payload directly, so the ported onPayload hook (see [MistralOptions]) sees
 * and returns the snake_case wire object.
 */
object MistralConversationsPayload {

    /** Mistral tool call IDs must be 9 alphanumeric characters. */
    const val MISTRAL_TOOL_CALL_ID_LENGTH = 9

    /** Builds the `v1/chat/completions` request body. */
    fun buildRequestBody(
        model: Model,
        context: Context,
        messages: List<JsonObject>,
        options: MistralOptions,
    ): JsonObject = buildJsonObject {
        put("model", model.id)
        put("stream", true)
        put("messages", JsonArray(messages))
        if (context.tools.isNotEmpty()) {
            put("tools", JsonArray(context.tools.map { toFunctionTool(it) }))
        }
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("max_tokens", it) }
        options.toolChoice?.let { put("tool_choice", mapToolChoice(it)) }
        options.promptMode?.let { put("prompt_mode", it.wire) }
        options.reasoningEffort?.let { put("reasoning_effort", it) }
        if (shouldUsePromptCaching(options)) {
            requireNotNull(options.sessionId) // guarded by shouldUsePromptCaching
            put("prompt_cache_key", options.sessionId)
        }
    }

    fun shouldUsePromptCaching(options: MistralOptions): Boolean =
        options.cacheRetention != CacheRetention.NONE && options.sessionId != null

    private fun mapToolChoice(choice: ToolChoice): kotlinx.serialization.json.JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Any -> JsonPrimitive("any")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Function -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice.name) })
        }
    }

    /** Mistral always supports strict mode, so the schema is rewritten when strict applies. */
    private fun toFunctionTool(tool: Tool): JsonObject {
        val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictMode = true)
        return buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", getJsonSchemaToolParameters(tool, strict))
                    put("strict", strict ?: false)
                },
            )
        }
    }

    /**
     * Converts transformed messages (see [transformMessages]) to Mistral wire
     * messages: thinking is already plain text, tool call IDs are already
     * normalized (with tool results remapped), and orphaned tool calls already
     * have synthetic results.
     */
    fun toChatMessages(
        messages: List<Message>,
        supportsImages: Boolean,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    val content = (msg as works.resolve.pathfinder.ai.core.UserMessage).content
                    // A single-text-block message is the Kotlin equivalent of
                    // pi's plain-text content string for user prompts.
                    if (content.size == 1 && content[0].type == ContentType.TEXT) {
                        result.add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    sanitize(content[0] as TextContent),
                                )
                            },
                        )
                        continue
                    }
                    val hadImages = content.any { it.type == ContentType.IMAGE }
                    val chunks = buildJsonArray {
                        for (item in content) {
                            if (item.type == ContentType.TEXT) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", sanitize(item as TextContent))
                                    },
                                )
                            } else if (supportsImages && item.type == ContentType.IMAGE) {
                                add(imageChunk(item as works.resolve.pathfinder.ai.core.ImageContent))
                            }
                        }
                    }
                    if (chunks.size > 0) {
                        result.add(buildJsonObject {
                            put("role", "user")
                            put("content", chunks)
                        })
                        continue
                    }
                    if (hadImages && !supportsImages) {
                        result.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "(image omitted: model does not support images)")
                            },
                        )
                    }
                }

                MessageRole.ASSISTANT -> {
                    val assistant = msg as AssistantMessage
                    val contentParts = mutableListOf<JsonObject>()
                    val toolCalls = mutableListOf<JsonObject>()

                    for (block in assistant.content) {
                        when (block.type) {
                            ContentType.TEXT -> {
                                val text = sanitize(block as TextContent)
                                if (text.trim().isNotEmpty()) {
                                    contentParts.add(
                                        buildJsonObject { put("type", "text"); put("text", text) },
                                    )
                                }
                            }
                            ContentType.THINKING -> {
                                val thinking = sanitizeText((block as works.resolve.pathfinder.ai.core.ThinkingContent).thinking)
                                if (thinking.trim().isNotEmpty()) {
                                    contentParts.add(
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put(
                                                "thinking",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "text")
                                                            put("text", thinking)
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            ContentType.TOOL_CALL -> {
                                val call = block as works.resolve.pathfinder.ai.core.ToolCall
                                toolCalls.add(
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments.ifEmpty { "{}" })
                                            },
                                        )
                                        put("index", 0)
                                    },
                                )
                            }
                            ContentType.IMAGE -> Unit
                        }
                    }

                    val wireMessage = buildJsonObject {
                        put("role", "assistant")
                        put("prefix", false)
                        if (contentParts.isNotEmpty()) put("content", JsonArray(contentParts))
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", JsonArray(toolCalls))
                        }
                    }
                    if (contentParts.isNotEmpty() || toolCalls.isNotEmpty()) result.add(wireMessage)
                }

                MessageRole.TOOL_RESULT -> {
                    val toolMsg = msg as works.resolve.pathfinder.ai.core.ToolResultMessage
                    val textResult = toolMsg.content
                        .filter { it.type == ContentType.TEXT }
                        .joinToString("\n") { sanitize(it as TextContent) }
                    val hasImages = toolMsg.content.any { it.type == ContentType.IMAGE }
                    val toolText = buildToolResultText(textResult, hasImages, supportsImages, toolMsg.isError)
                    val contentChunks = buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", toolText) })
                        for (part in toolMsg.content) {
                            if (!supportsImages) continue
                            if (part.type != ContentType.IMAGE) continue
                            add(imageChunk(part as works.resolve.pathfinder.ai.core.ImageContent))
                        }
                    }
                    result.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", toolMsg.toolCallId)
                            put("name", toolMsg.toolName)
                            put("content", contentChunks)
                        },
                    )
                }
            }
        }

        return result
    }

    private fun imageChunk(image: works.resolve.pathfinder.ai.core.ImageContent): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put("image_url", "data:${image.mimeType};base64,${image.data}")
        }

    internal fun buildToolResultText(
        text: String,
        hasImages: Boolean,
        supportsImages: Boolean,
        isError: Boolean,
    ): String {
        val trimmed = text.trim()
        val errorPrefix = if (isError) "[tool error] " else ""

        if (trimmed.isNotEmpty()) {
            val imageSuffix =
                if (hasImages && !supportsImages) "\n[tool image omitted: model does not support images]" else ""
            return "$errorPrefix$trimmed$imageSuffix"
        }

        if (hasImages) {
            if (supportsImages) {
                return if (isError) "[tool error] (see attached image)" else "(see attached image)"
            }
            return if (isError) {
                "[tool error] (image omitted: model does not support images)"
            } else {
                "(image omitted: model does not support images)"
            }
        }

        return if (isError) "[tool error] (no tool output)" else "(no tool output)"
    }

    private fun sanitize(content: TextContent): String = sanitizeText(content.text)

    private fun sanitizeText(text: String): String = sanitizeSurrogates(text)
}

/** Normalizes arbitrary tool call IDs to Mistral's 9-character alphanumeric format, avoiding collisions. */
class MistralToolCallIdNormalizer {
    private val idMap = mutableMapOf<String, String>()
    private val reverseMap = mutableMapOf<String, String>()

    fun normalize(id: String): String {
        idMap[id]?.let { return it }

        var attempt = 0
        while (true) {
            val candidate = deriveMistralToolCallId(id, attempt)
            val owner = reverseMap[candidate]
            if (owner == null || owner == id) {
                idMap[id] = candidate
                reverseMap[candidate] = id
                return candidate
            }
            attempt++
        }
    }
}

internal fun deriveMistralToolCallId(id: String, attempt: Int): String {
    val normalized = id.replace(Regex("[^a-zA-Z0-9]"), "")
    if (attempt == 0 && normalized.length == MistralConversationsPayload.MISTRAL_TOOL_CALL_ID_LENGTH) {
        return normalized
    }
    val seedBase = normalized.ifEmpty { id }
    val seed = if (attempt == 0) seedBase else "$seedBase:$attempt"
    return shortHash(seed)
        .replace(Regex("[^a-zA-Z0-9]"), "")
        .take(MistralConversationsPayload.MISTRAL_TOOL_CALL_ID_LENGTH)
}
