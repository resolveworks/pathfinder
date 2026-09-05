package works.resolve.pathfinder.codingagent.core.session

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * JSONL v3 session codec, pi's session file format: line 0 is the session
 * header, then one entry per line with pi's exact field names. Entry
 * timestamps on the wire are pi's ISO-8601 UTC strings with exactly three
 * millisecond digits; internally entries carry epoch millis.
 *
 * Divergences from pi:
 * - `cwd` is written as a constant empty string (Android has no working
 *   directory) and ignored on read.
 * - Decode is permissive like pi's parseSessionEntryLine but entry payloads
 *   decode into typed [SessionEntry]s: any line that fails typed decode is
 *   skipped, and unknown entry `type`s are skipped (pi reads them as raw
 *   objects for extension entries; no extension entries exist here).
 * - Old "v4" files are unreadable (their first line is not a `session`
 *   header) and no migration exists, per AGENTS.md.
 */
internal object JsonlCodec {
    const val SESSION_VERSION = 3

    data class SessionHeader(val id: String, val timestamp: Long)

    /** Header or entry produced by one line. */
    sealed interface Line {
        data class Header(val header: SessionHeader) : Line

        data class Entry(val entry: SessionEntry) : Line
    }

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    fun formatIso(millis: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC))

    fun parseIso(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()

    /** pi's file name: the header timestamp with ':'/'.' replaced, then the session id. */
    fun sessionFileName(headerTimestampMillis: Long, sessionId: String): String =
        formatIso(headerTimestampMillis).replace(":", "-").replace(".", "-") + "_$sessionId.jsonl"

    fun encodeHeaderLine(header: SessionHeader): String = buildJsonObject {
        put("type", "session")
        put("version", SESSION_VERSION)
        put("id", header.id)
        put("timestamp", formatIso(header.timestamp))
        put("cwd", "")
    }.toString() + "\n"

    fun encodeEntryLine(entry: SessionEntry): String = buildJsonObject {
        put("id", entry.id)
        put("parentId", entry.parentId)
        put("timestamp", formatIso(entry.timestamp))
        when (entry) {
            is MessageEntry -> {
                put("type", "message")
                put("message", encodeMessage(entry.message))
            }

            is CompactionEntry -> {
                put("type", "compaction")
                put("summary", entry.summary)
                put("firstKeptEntryId", entry.firstKeptEntryId)
                put("tokensBefore", entry.tokensBefore)
                entry.details?.let {
                    putJsonObject("details") {
                        put("readFiles", JsonArray(it.readFiles.map(::JsonPrimitive)))
                        put("modifiedFiles", JsonArray(it.modifiedFiles.map(::JsonPrimitive)))
                    }
                }
                entry.usage?.let { put("usage", encodeUsage(it)) }
            }

            is ModelChangeEntry -> {
                put("type", "model_change")
                put("provider", entry.provider)
                put("modelId", entry.modelId)
            }

            is ThinkingLevelEntry -> {
                put("type", "thinking_level_change")
                put("thinkingLevel", entry.thinkingLevel)
            }

            is BranchSummaryEntry -> {
                put("type", "branch_summary")
                put("fromId", entry.fromId)
                put("summary", entry.summary)
                entry.details?.let { put("details", it) }
                entry.usage?.let { put("usage", encodeUsage(it)) }
            }
        }
    }.toString() + "\n"

    /** One parsed line; null for blank, malformed, and unknown/skipped lines. */
    fun parseLine(line: String): Line? {
        if (line.isBlank()) return null
        val obj = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        return try {
            when (obj.string("type")) {
                "session" -> {
                    val id = obj.string("id") ?: return null
                    val timestamp = obj.string("timestamp")?.let(::parseIso) ?: return null
                    Line.Header(SessionHeader(id, timestamp))
                }

                "message", "compaction", "model_change", "thinking_level_change",
                "branch_summary" -> Line.Entry(decodeEntry(obj))

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeEntry(obj: JsonObject): SessionEntry {
        val id = obj.string("id") ?: invalid()
        val parentId = (obj["parentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val timestamp = obj.string("timestamp")?.let(::parseIso) ?: invalid()
        return when (val type = obj.string("type")) {
            "message" -> MessageEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                message = decodeMessage(obj["message"] ?: invalid())
            )

            "compaction" -> CompactionEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                summary = obj.string("summary") ?: invalid(),
                firstKeptEntryId = obj.string("firstKeptEntryId") ?: invalid(),
                tokensBefore = obj.number("tokensBefore")?.toInt() ?: invalid(),
                details = decodeDetails(obj["details"]),
                usage = obj["usage"]?.let(::decodeUsage)
            )

            "model_change" -> ModelChangeEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                provider = obj.string("provider") ?: invalid(),
                modelId = obj.string("modelId") ?: invalid()
            )

            "thinking_level_change" -> ThinkingLevelEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                thinkingLevel = obj.string("thinkingLevel") ?: invalid()
            )

            "branch_summary" -> BranchSummaryEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                fromId = obj.string("fromId") ?: invalid(),
                summary = obj.string("summary") ?: invalid(),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage)
            )

            else -> invalid("unknown entry type $type")
        }
    }

    private fun decodeDetails(
        element: kotlinx.serialization.json.JsonElement?
    ): CompactionDetails? {
        val obj = element as? JsonObject ?: return null
        return CompactionDetails(
            readFiles = decodeStringList(obj["readFiles"]),
            modifiedFiles = decodeStringList(obj["modifiedFiles"])
        )
    }

    private fun invalid(reason: String = "malformed session line"): Nothing =
        throw JsonlDecodeException(reason)

    class JsonlDecodeException(message: String) : Exception(message)

    /** Numeric (never string-encoded) field read for wire numbers. */
    private fun JsonObject.number(key: String): Double? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()

    // ---- message codecs (unchanged wire shape from the v4 codec) ----

    fun encodeMessage(message: Message): JsonObject = when (message) {
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
            // pi serializes the lowercase wire value.
            put("stopReason", message.stopReason.name.lowercase())
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
            message.usage?.let { put("usage", encodeUsage(it)) }
            if (message.addedToolNames.isNotEmpty()) {
                put("addedToolNames", JsonArray(message.addedToolNames.map(::JsonPrimitive)))
            }
        }
    }

    fun decodeMessage(element: kotlinx.serialization.json.JsonElement): Message {
        val obj = element as? JsonObject ?: invalid()
        return when (val role = obj.string("role")) {
            "user" -> UserMessage(
                content = decodeContentList(obj["content"]),
                timestamp = obj.number("timestamp")?.toLong() ?: invalid()
            )

            "assistant" -> AssistantMessage(
                content = decodeContentList(obj["content"]),
                api = obj.string("api") ?: invalid(),
                provider = obj.string("provider") ?: invalid(),
                model = obj.string("model") ?: invalid(),
                usage = decodeUsage(obj["usage"] ?: invalid()),
                // pi files carry lowercase wire values; old Pathfinder
                // files carried the enum name.
                stopReason = obj.string("stopReason")
                    ?.let {
                        runCatching {
                            works.resolve.pathfinder.ai.StopReason.valueOf(it.uppercase())
                        }.getOrNull()
                    }
                    ?: invalid(),
                errorMessage = obj.string("errorMessage"),
                rawStopReason = obj.string("rawStopReason"),
                responseId = obj.string("responseId"),
                responseModel = obj.string("responseModel"),
                endTurn = obj["endTurn"]?.let { (it as JsonPrimitive).content.toBooleanStrict() },
                timestamp = obj.number("timestamp")?.toLong() ?: invalid()
            )

            "toolResult" -> ToolResultMessage(
                toolCallId = obj.string("toolCallId") ?: invalid(),
                toolName = obj.string("toolName") ?: invalid(),
                content = decodeContentList(obj["content"]),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
                addedToolNames = decodeStringList(obj["addedToolNames"]),
                isError =
                    obj["isError"]?.let { (it as JsonPrimitive).content.toBooleanStrict() }
                        ?: invalid(),
                timestamp = obj.number("timestamp")?.toLong() ?: invalid()
            )

            else -> invalid("unknown message role $role")
        }
    }

    private fun decodeStringList(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.stringOrNull() }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putUsage(usage: Usage) {
        put("input", usage.input)
        put("output", usage.output)
        put("cacheRead", usage.cacheRead)
        put("cacheWrite", usage.cacheWrite)
        if (usage.cacheWrite1h > 0) put("cacheWrite1h", usage.cacheWrite1h)
        if (usage.reasoning != 0) put("reasoning", usage.reasoning)
        put("totalTokens", usage.totalTokens)
        putJsonObject("cost") {
            put("input", usage.cost.input)
            put("output", usage.cost.output)
            put("cacheRead", usage.cost.cacheRead)
            put("cacheWrite", usage.cost.cacheWrite)
            put("total", usage.cost.total)
        }
    }

    private fun encodeUsage(usage: Usage): JsonObject = buildJsonObject { putUsage(usage) }

    private fun decodeUsage(element: kotlinx.serialization.json.JsonElement): Usage {
        val obj = element as? JsonObject ?: invalid()
        val cost = obj["cost"] as? JsonObject ?: invalid()
        val c = { key: String -> cost.number(key) ?: invalid() }
        fun i(key: String) = obj.number(key)?.toInt() ?: invalid()
        return Usage(
            input = i("input"),
            output = i("output"),
            cacheRead = i("cacheRead"),
            cacheWrite = i("cacheWrite"),
            cacheWrite1h = obj.number("cacheWrite1h")?.toInt() ?: 0,
            // pi omits reasoning when zero.
            reasoning = obj.number("reasoning")?.toInt() ?: 0,
            totalTokens = i("totalTokens"),
            cost = Cost(
                input = c("input"),
                output = c("output"),
                cacheRead = c("cacheRead"),
                cacheWrite = c("cacheWrite"),
                total = c("total")
            )
        )
    }

    private fun encodeContentList(content: List<Content>): JsonArray =
        JsonArray(content.map(::encodeContent))

    private fun decodeContentList(element: kotlinx.serialization.json.JsonElement?): List<Content> {
        val array = element as? JsonArray ?: invalid()
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

    private fun decodeContent(element: kotlinx.serialization.json.JsonElement): Content {
        val obj = element as? JsonObject ?: invalid()
        return when (val type = obj.string("type")) {
            "text" -> TextContent(
                text = obj.string("text") ?: invalid(),
                textSignature = obj.string("textSignature")
            )

            "thinking" -> ThinkingContent(
                thinking = obj.string("thinking") ?: invalid(),
                thinkingSignature = obj.string("thinkingSignature"),
                redacted = obj.string("redacted") == "true"
            )

            "image" -> ImageContent(
                data = obj.string("data") ?: invalid(),
                mimeType = obj.string("mimeType") ?: invalid()
            )

            "toolCall" -> ToolCall(
                id = obj.string("id") ?: invalid(),
                name = obj.string("name") ?: invalid(),
                arguments = obj.string("arguments") ?: invalid(),
                thoughtSignature = obj.string("thoughtSignature"),
                namespace = obj.string("namespace")
            )

            else -> invalid("unknown content type $type")
        }
    }
}
