package works.resolve.aletheia.data.sessions

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Content
import works.resolve.aletheia.ai.core.ContentType
import works.resolve.aletheia.ai.core.Cost
import works.resolve.aletheia.ai.core.ImageContent
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.MessageRole
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.Usage
import works.resolve.aletheia.ai.core.UserMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Manual JSON-DOM codec for [Session] files. Format: one file per session
 * containing only transcript data — never API keys, request options, or
 * provider/model catalogs. Unknown or malformed data is rejected with a
 * [SessionDataException]; nothing is silently dropped.
 */
internal object SessionCodec {

    private const val FORMAT_VERSION = 2

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(session: Session): String = buildJsonObject {
        put("format", FORMAT_VERSION)
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("entries", JsonArray(session.entries.map(::encodeEntry)))
        put("leafId", session.leafId)
    }.toString()

    fun decode(text: String): Session {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SessionDataException("Malformed session data", e)
        }
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: expected object")
        val version = obj.int("format") ?: throw SessionDataException("Unsupported session format")
        if (version !in 1..FORMAT_VERSION) throw SessionDataException("Unsupported session format: $version")

        return when (version) {
            1 -> migrateV1(obj)
            else -> decodeV2(obj)
        }
    }

    private fun decodeV2(obj: JsonObject): Session {
        val entries = (obj["entries"] as? JsonArray)
            ?: throw SessionDataException("Malformed session data: missing entries")
        return Session(
            id = requireId(obj.string("id") ?: throw SessionDataException("Malformed session data: missing id")),
            title = obj.string("title") ?: throw SessionDataException("Malformed session data: missing title"),
            createdAt = obj.long("createdAt") ?: throw SessionDataException("Malformed session data: missing createdAt"),
            updatedAt = obj.long("updatedAt") ?: throw SessionDataException("Malformed session data: missing updatedAt"),
            entries = entries.map(::decodeEntry),
            leafId = obj.string("leafId"),
        )
    }

    /** v1 files stored a flat message list; chain them into entries with
     * Conversation.fromMessages semantics (leaf = last entry or null). */
    private fun migrateV1(obj: JsonObject): Session {
        val messages = (obj["messages"] as? JsonArray)
            ?: throw SessionDataException("Malformed session data: missing messages")
        val conversation = Conversation.fromMessages(messages.map(::decodeMessage))
        return Session(
            id = requireId(obj.string("id") ?: throw SessionDataException("Malformed session data: missing id")),
            title = obj.string("title") ?: throw SessionDataException("Malformed session data: missing title"),
            createdAt = obj.long("createdAt") ?: throw SessionDataException("Malformed session data: missing createdAt"),
            updatedAt = obj.long("updatedAt") ?: throw SessionDataException("Malformed session data: missing updatedAt"),
            entries = conversation.entries,
            leafId = conversation.leafId,
        )
    }

    // ---- Entries ----

    private fun encodeEntry(entry: SessionEntry): JsonObject = when (entry) {
        is MessageEntry -> buildJsonObject {
            put("type", "message")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("message", encodeMessage(entry.message))
        }
    }

    private fun decodeEntry(element: JsonElement): SessionEntry {
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: entry must be an object")
        val id = obj.string("id") ?: throw SessionDataException("Malformed session data: entry missing id")
        return when (val type = obj.string("type")) {
            "message" -> MessageEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.long("timestamp")
                    ?: throw SessionDataException("Malformed session data: entry missing timestamp"),
                message = decodeMessage(
                    obj["message"] ?: throw SessionDataException("Malformed session data: entry missing message"),
                ),
            )

            else -> throw SessionDataException("Unknown entry type: $type")
        }
    }

    /** Strictly-required long (present, correct type). */
    private fun JsonObject.requireLong(key: String, what: String): Long =
        long(key) ?: throw SessionDataException("Malformed session data: missing $what")

    // ---- Messages ----

    private fun encodeMessage(message: Message): JsonObject = when (message) {
        is UserMessage -> buildJsonObject {
            put("role", "user")
            put("timestamp", message.timestamp)
            put("content", encodeContentList(message.content))
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "assistant")
            put("timestamp", message.timestamp)
            put("content", encodeContentList(message.content))
            put("api", message.api)
            put("provider", message.provider)
            put("model", message.model)
            putJsonObject("usage") {
                put("input", message.usage.input)
                put("output", message.usage.output)
                put("cacheRead", message.usage.cacheRead)
                put("cacheWrite", message.usage.cacheWrite)
                put("reasoning", message.usage.reasoning)
                put("totalTokens", message.usage.totalTokens)
                putJsonObject("cost") {
                    put("input", message.usage.cost.input)
                    put("output", message.usage.cost.output)
                    put("cacheRead", message.usage.cost.cacheRead)
                    put("cacheWrite", message.usage.cost.cacheWrite)
                    put("total", message.usage.cost.total)
                }
            }
            put("stopReason", message.stopReason.name)
            message.errorMessage?.let { put("errorMessage", it) }
            message.rawStopReason?.let { put("rawStopReason", it) }
            message.responseId?.let { put("responseId", it) }
            message.responseModel?.let { put("responseModel", it) }
            message.endTurn?.let { put("endTurn", it) }
        }

        is ToolResultMessage -> buildJsonObject {
            put("role", "toolResult")
            put("timestamp", message.timestamp)
            put("toolCallId", message.toolCallId)
            put("toolName", message.toolName)
            put("content", encodeContentList(message.content))
            put("isError", message.isError)
        }
    }

    private fun decodeMessage(element: JsonElement): Message {
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: message must be an object")
        return when (val role = obj.string("role")) {
            "user" -> UserMessage(
                content = decodeContentList(obj["content"]),
                timestamp = obj.requireLong("timestamp", "message timestamp"),
            )

            "assistant" -> AssistantMessage(
                content = decodeContentList(obj["content"]),
                api = obj.string("api") ?: throw SessionDataException("Malformed session data: assistant message missing api"),
                provider = obj.string("provider") ?: throw SessionDataException("Malformed session data: assistant message missing provider"),
                model = obj.string("model") ?: throw SessionDataException("Malformed session data: assistant message missing model"),
                usage = decodeUsage(obj["usage"]),
                stopReason = decodeStopReason(obj.string("stopReason")),
                errorMessage = obj.string("errorMessage"),
                rawStopReason = obj.string("rawStopReason"),
                responseId = obj.string("responseId"),
                responseModel = obj.string("responseModel"),
                endTurn = obj.boolean("endTurn"),
                timestamp = obj.requireLong("timestamp", "message timestamp"),
            )

            "toolResult" -> ToolResultMessage(
                toolCallId = obj.string("toolCallId") ?: throw SessionDataException("Malformed session data: tool result missing toolCallId"),
                toolName = obj.string("toolName") ?: throw SessionDataException("Malformed session data: tool result missing toolName"),
                content = decodeContentList(obj["content"]),
                isError = obj.boolean("isError")
                    ?: throw SessionDataException("Malformed session data: tool result missing isError"),
                timestamp = obj.requireLong("timestamp", "message timestamp"),
            )

            else -> throw SessionDataException("Unknown message role: $role")
        }
    }

    private fun decodeStopReason(name: String?): StopReason =
        name?.let { runCatching { StopReason.valueOf(it) }.getOrNull() }
            ?: throw SessionDataException("Malformed session data: unknown stop reason $name")

    private fun decodeUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: throw SessionDataException("Malformed session data: missing usage")
        fun requireInt(key: String): Int =
            obj.int(key) ?: throw SessionDataException("Malformed session data: usage missing $key")
        val cost = (obj["cost"] as? JsonObject)?.let { c ->
            fun requireDouble(key: String): Double =
                c.double(key) ?: throw SessionDataException("Malformed session data: cost missing $key")
            Cost(
                input = requireDouble("input"),
                output = requireDouble("output"),
                cacheRead = requireDouble("cacheRead"),
                cacheWrite = requireDouble("cacheWrite"),
                total = requireDouble("total"),
            )
        } ?: throw SessionDataException("Malformed session data: usage missing cost")
        return Usage(
            input = requireInt("input"),
            output = requireInt("output"),
            cacheRead = requireInt("cacheRead"),
            cacheWrite = requireInt("cacheWrite"),
            reasoning = requireInt("reasoning"),
            totalTokens = requireInt("totalTokens"),
            cost = cost,
        )
    }

    // ---- Content ----

    private fun encodeContentList(content: List<Content>): JsonArray =
        JsonArray(content.map(::encodeContent))

    private fun decodeContentList(element: JsonElement?): List<Content> {
        val array = element as? JsonArray ?: throw SessionDataException("Malformed session data: missing content")
        return array.map(::decodeContent)
    }

    private fun encodeContent(content: Content): JsonObject = when (content) {
        is TextContent -> buildJsonObject {
            put("type", "text")
            put("text", content.text)
            content.textSignature?.let { put("textSignature", it) }
        }

        is ThinkingContent -> buildJsonObject {
            put("type", "thinking")
            put("thinking", content.thinking)
            content.thinkingSignature?.let { put("thinkingSignature", it) }
            if (content.redacted) put("redacted", true)
        }

        is ImageContent -> buildJsonObject {
            put("type", "image")
            put("data", content.data)
            put("mimeType", content.mimeType)
        }

        is ToolCall -> buildJsonObject {
            put("type", "toolCall")
            put("id", content.id)
            put("name", content.name)
            put("arguments", content.arguments)
            content.thoughtSignature?.let { put("thoughtSignature", it) }
            content.namespace?.let { put("namespace", it) }
        }
    }

    private fun decodeContent(element: JsonElement): Content {
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: content must be an object")
        return when (val type = obj.string("type")) {
            "text" -> TextContent(
                text = obj.string("text") ?: throw SessionDataException("Malformed session data: text content missing text"),
                textSignature = obj.string("textSignature"),
            )

            "thinking" -> ThinkingContent(
                thinking = obj.string("thinking") ?: throw SessionDataException("Malformed session data: thinking content missing thinking"),
                thinkingSignature = obj.string("thinkingSignature"),
                redacted = obj.boolean("redacted") ?: false,
            )

            "image" -> ImageContent(
                data = obj.string("data") ?: throw SessionDataException("Malformed session data: image content missing data"),
                mimeType = obj.string("mimeType") ?: throw SessionDataException("Malformed session data: image content missing mimeType"),
            )

            "toolCall" -> ToolCall(
                id = obj.string("id") ?: throw SessionDataException("Malformed session data: tool call missing id"),
                name = obj.string("name") ?: throw SessionDataException("Malformed session data: tool call missing name"),
                arguments = obj.string("arguments") ?: throw SessionDataException("Malformed session data: tool call missing arguments"),
                thoughtSignature = obj.string("thoughtSignature"),
                namespace = obj.string("namespace"),
            )

            else -> throw SessionDataException("Unknown content type: $type")
        }
    }

    // ---- Json object helpers ----

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
