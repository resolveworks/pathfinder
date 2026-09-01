package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.agent.compaction.CompactionDetails
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.requireDouble
import works.resolve.pathfinder.ai.utils.requireInt
import works.resolve.pathfinder.ai.utils.requireLong
import works.resolve.pathfinder.ai.utils.strictBoolean
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.strictInt
import works.resolve.pathfinder.ai.utils.strictLong
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * Manual JSON-DOM codec for [Session] files. Format: one file per session
 * containing only transcript data — never API keys, request options, or
 * provider/model catalogs. Unknown or malformed data is rejected with a
 * [SessionDataException]; nothing is silently dropped.
 */
internal object SessionCodec {

    private const val FORMAT_VERSION = 3

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
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SessionDataException("Malformed session data", e)
        }
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: expected object")
        val version = obj.strictInt("format") ?: throw SessionDataException("Unsupported session format")
        if (version != FORMAT_VERSION) throw SessionDataException("Unsupported session format: $version")
        return decodeSession(obj)
    }

    private fun decodeSession(obj: JsonObject): Session {
        val entries = obj.arr("entries")
            ?: throw SessionDataException("Malformed session data: missing entries")
        return Session(
            id = requireId(obj.string("id") ?: throw SessionDataException("Malformed session data: missing id")),
            title = obj.string("title") ?: throw SessionDataException("Malformed session data: missing title"),
            createdAt = obj.strictLong("createdAt") ?: throw SessionDataException("Malformed session data: missing createdAt"),
            updatedAt = obj.strictLong("updatedAt") ?: throw SessionDataException("Malformed session data: missing updatedAt"),
            entries = entries.map(::decodeEntry),
            leafId = obj.string("leafId"),
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

        // pi harness CompactionEntry (session/types.ts): summary + retained
        // tail + compaction metadata.
        is CompactionEntry -> buildJsonObject {
            put("type", "compaction")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("summary", entry.summary)
            put("tokensBefore", entry.tokensBefore)
            put("retainedTail", JsonArray(entry.retainedTail.map(::encodeMessage)))
            entry.details?.let {
                putJsonObject("details") {
                    put("readFiles", JsonArray(it.readFiles.map(::JsonPrimitive)))
                    put("modifiedFiles", JsonArray(it.modifiedFiles.map(::JsonPrimitive)))
                }
            }
            entry.usage?.let { put("usage", buildJsonObject { putUsage(it) }) }
        }

        // pi harness entry kinds (session/jsonl/codec.ts ENTRY_TYPES). Format
        // 3 adds model_change, thinking_level_change, active_tools_change,
        // branch_summary, and custom; format 2 and older are rejected outright
        // (disposable-data policy, no migration path).
        is ModelChangeEntry -> buildJsonObject {
            put("type", "model_change")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("provider", entry.provider)
            put("modelId", entry.modelId)
        }

        is ThinkingLevelEntry -> buildJsonObject {
            put("type", "thinking_level_change")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("thinkingLevel", entry.thinkingLevel)
        }

        is ActiveToolsEntry -> buildJsonObject {
            put("type", "active_tools_change")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("activeToolNames", JsonArray(entry.activeToolNames.map(::JsonPrimitive)))
        }

        is BranchSummaryEntry -> buildJsonObject {
            put("type", "branch_summary")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("fromId", entry.fromId)
            put("summary", entry.summary)
            entry.details?.let { put("details", it) }
            entry.usage?.let { put("usage", buildJsonObject { putUsage(it) }) }
        }

        is CustomEntry -> buildJsonObject {
            put("type", "custom")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("customType", entry.customType)
            entry.data?.let { put("data", it) }
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
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: missing message timestamp")
                },
                message = decodeMessage(
                    obj["message"] ?: throw SessionDataException("Malformed session data: entry missing message"),
                ),
            )

            "compaction" -> CompactionEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                summary = obj.string("summary")
                    ?: throw SessionDataException("Malformed session data: compaction entry missing summary"),
                retainedTail = (obj.arr("retainedTail") ?: emptyList()).map(::decodeMessage),
                tokensBefore = obj.strictInt("tokensBefore")
                    ?: throw SessionDataException("Malformed session data: compaction entry missing tokensBefore"),
                details = obj.obj("details")?.let { d ->
                    CompactionDetails(
                        readFiles = d.stringList("readFiles"),
                        modifiedFiles = d.stringList("modifiedFiles"),
                    )
                },
                usage = obj["usage"]?.let(::decodeUsage),
            )

            "model_change" -> ModelChangeEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                provider = obj.string("provider")
                    ?: throw SessionDataException("Malformed session data: model_change entry missing provider"),
                modelId = obj.string("modelId")
                    ?: throw SessionDataException("Malformed session data: model_change entry missing modelId"),
            )

            "thinking_level_change" -> ThinkingLevelEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                thinkingLevel = obj.string("thinkingLevel")
                    ?: throw SessionDataException("Malformed session data: thinking_level_change entry missing thinkingLevel"),
            )

            "active_tools_change" -> ActiveToolsEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                activeToolNames = obj.stringList("activeToolNames"),
            )

            "branch_summary" -> BranchSummaryEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                fromId = obj.string("fromId")
                    ?: throw SessionDataException("Malformed session data: branch_summary entry missing fromId"),
                summary = obj.string("summary")
                    ?: throw SessionDataException("Malformed session data: branch_summary entry missing summary"),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
            )

            "custom" -> CustomEntry(
                id = id,
                parentId = obj.string("parentId"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: entry missing timestamp")
                },
                customType = obj.string("customType")
                    ?: throw SessionDataException("Malformed session data: custom entry missing customType"),
                data = obj["data"],
            )

            else -> throw SessionDataException("Unknown entry type: $type")
        }
    }

    // ---- Messages ----

    private fun kotlinx.serialization.json.JsonObjectBuilder.putUsage(usage: Usage) {
        put("input", usage.input)
        put("output", usage.output)
        put("cacheRead", usage.cacheRead)
        put("cacheWrite", usage.cacheWrite)
        if (usage.cacheWrite1h > 0) put("cacheWrite1h", usage.cacheWrite1h)
        put("reasoning", usage.reasoning)
        put("totalTokens", usage.totalTokens)
        putJsonObject("cost") {
            put("input", usage.cost.input)
            put("output", usage.cost.output)
            put("cacheRead", usage.cost.cacheRead)
            put("cacheWrite", usage.cost.cacheWrite)
            put("total", usage.cost.total)
        }
    }

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
            putJsonObject("usage") { putUsage(message.usage) }
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
            message.details?.let { put("details", it) }
            message.usage?.let { put("usage", buildJsonObject { putUsage(it) }) }
            if (message.addedToolNames.isNotEmpty()) {
                put("addedToolNames", JsonArray(message.addedToolNames.map(::JsonPrimitive)))
            }
        }
    }

    private fun decodeMessage(element: JsonElement): Message {
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: message must be an object")
        return when (val role = obj.string("role")) {
            "user" -> UserMessage(
                content = decodeContentList(obj["content"]),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: missing message timestamp")
                },
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
                endTurn = obj.strictBoolean("endTurn"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: missing message timestamp")
                },
            )

            "toolResult" -> ToolResultMessage(
                toolCallId = obj.string("toolCallId") ?: throw SessionDataException("Malformed session data: tool result missing toolCallId"),
                toolName = obj.string("toolName") ?: throw SessionDataException("Malformed session data: tool result missing toolName"),
                content = decodeContentList(obj["content"]),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
                addedToolNames = decodeStringList(obj["addedToolNames"]),
                isError = obj.strictBoolean("isError")
                    ?: throw SessionDataException("Malformed session data: tool result missing isError"),
                timestamp = obj.requireLong("timestamp") {
                    SessionDataException("Malformed session data: missing message timestamp")
                },
            )

            else -> throw SessionDataException("Unknown message role: $role")
        }
    }

    private fun decodeStopReason(name: String?): StopReason =
        name?.let { runCatching { StopReason.valueOf(it) }.getOrNull() }
            ?: throw SessionDataException("Malformed session data: unknown stop reason $name")

    private fun decodeUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: throw SessionDataException("Malformed session data: missing usage")
        val cost = obj.obj("cost")?.let { c ->
            Cost(
                input = c.requireDouble("input") { key -> SessionDataException("Malformed session data: cost missing $key") },
                output = c.requireDouble("output") { key -> SessionDataException("Malformed session data: cost missing $key") },
                cacheRead = c.requireDouble("cacheRead") { key -> SessionDataException("Malformed session data: cost missing $key") },
                cacheWrite = c.requireDouble("cacheWrite") { key -> SessionDataException("Malformed session data: cost missing $key") },
                total = c.requireDouble("total") { key -> SessionDataException("Malformed session data: cost missing $key") },
            )
        } ?: throw SessionDataException("Malformed session data: usage missing cost")
        return Usage(
            input = obj.requireInt("input") { key -> SessionDataException("Malformed session data: usage missing $key") },
            output = obj.requireInt("output") { key -> SessionDataException("Malformed session data: usage missing $key") },
            cacheRead = obj.requireInt("cacheRead") { key -> SessionDataException("Malformed session data: usage missing $key") },
            cacheWrite = obj.requireInt("cacheWrite") { key -> SessionDataException("Malformed session data: usage missing $key") },
            cacheWrite1h = obj.strictInt("cacheWrite1h") ?: 0,
            reasoning = obj.requireInt("reasoning") { key -> SessionDataException("Malformed session data: usage missing $key") },
            totalTokens = obj.requireInt("totalTokens") { key -> SessionDataException("Malformed session data: usage missing $key") },
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

    /** Strict string array; absence is an empty list, malformed values are rejected. */
    private fun JsonObject.stringList(key: String): List<String> {
        val array = this.arr(key)
            ?: throw SessionDataException("Malformed session data: $key must be an array")
        return array.map { value ->
            value.stringOrNull()
                ?: throw SessionDataException("Malformed session data: $key must contain strings")
        }
    }

    /** Optional string array; absence uses the current model default. */
    private fun decodeStringList(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        val array = element as? JsonArray
            ?: throw SessionDataException("Malformed session data: addedToolNames must be an array")
        return array.map { value ->
            value.stringOrNull()
                ?: throw SessionDataException("Malformed session data: addedToolNames must contain strings")
        }
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
                redacted = obj.strictBoolean("redacted") ?: false,
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

}
