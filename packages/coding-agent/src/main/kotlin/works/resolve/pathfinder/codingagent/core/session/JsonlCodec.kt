package works.resolve.pathfinder.codingagent.core.session

import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.requireDouble
import works.resolve.pathfinder.ai.utils.requireInt
import works.resolve.pathfinder.ai.utils.requireLong
import works.resolve.pathfinder.ai.utils.strictBoolean
import works.resolve.pathfinder.ai.utils.strictInt
import works.resolve.pathfinder.ai.utils.strictLong
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * JSONL v4 session codec: a session file is one header line followed by
 * one [SessionMutation] line per append. Malformed input is rejected with
 * [JsonlDecodeError], whose [kind] distinguishes JSON syntax errors (only
 * ever producible by a torn final append — the load path's torn-tail
 * repair signal) from schema violations.
 *
 * Divergences from pi:
 * - `cwd` is a required header field upstream; Android has no working
 *   directory, so Pathfinder never writes it and decode accepts its
 *   absence (a pi-written session's cwd decodes and is ignored).
 * - Entry payloads decode into Pathfinder's typed [SessionEntry] hierarchy
 *   (rejecting unknown/malformed fields) rather than pi's permissive field
 *   spread; the line shapes are otherwise identical.
 * - Old formats are rejected, never migrated (AGENTS.md): a header whose
 *   `version` is not 4 fails the version check ("has unsupported session
 *   version", exactly pi's pin-era codec message), and a genuine v3 header
 *   line (`{"type":"session",...}`, no `kind:"header"`) fails the earlier
 *   kind check — both [JsonlDecodeError.Kind.SCHEMA]. pi's v3→v4 migration
 *   (`jsonl/legacy-v3.ts`) is post-pin drift (absent at b8b873b98) and is
 *   deliberately not ported; the `legacyParentSessionPath` field a migrated
 *   file carries is still decoded and preserved verbatim.
 */
internal object JsonlCodec {

    class JsonlDecodeError(
        val kind: Kind,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause) {
        enum class Kind { SYNTAX, SCHEMA }
    }

    data class JsonlV4Header(
        val id: String,
        val createdAt: Long,
        val parentSessionId: String? = null,
        /** Preserved only when a v3 parent path could not be resolved. */
        val legacyParentSessionPath: String? = null,
        /** Opaque application-owned metadata. */
        val metadata: JsonObject? = null,
    )

    private val ENTRY_TYPES = setOf(
        "message", "model_change", "thinking_level_change", "active_tools_change",
        "compaction", "branch_summary", "custom",
    )

    private val RECORD_TYPES = setOf(
        "operation_started", "abort_requested", "operation_finished", "step_attempt",
        "tool_started", "queue_enqueued", "queue_cancelled", "write_deferred", "usage",
    )

    /** The header line, newline-terminated. */
    fun encodeHeader(header: JsonlV4Header): String = buildJsonObject {
        put("kind", "header")
        put("version", 4)
        put("id", header.id)
        put("createdAt", header.createdAt)
        header.parentSessionId?.let { put("parentSessionId", it) }
        header.legacyParentSessionPath?.let { put("legacyParentSessionPath", it) }
        header.metadata?.let { put("metadata", it) }
    }.toString() + "\n"

    fun decodeHeader(line: String): JsonlV4Header {
        val value = parseObject(line)
        if (value.string("kind") != "header") schema("is not a header")
        if (value.strictInt("version") != 4) schema("has unsupported session version")
        val parentSessionId = value.string("parentSessionId")
        if ("parentSessionId" in value && parentSessionId == null) schema("has invalid parentSessionId")
        val legacyParentSessionPath = value.string("legacyParentSessionPath")
        if ("legacyParentSessionPath" in value && legacyParentSessionPath == null) {
            schema("has invalid legacyParentSessionPath")
        }
        if (parentSessionId != null && legacyParentSessionPath != null) {
            schema("has both parentSessionId and legacyParentSessionPath")
        }
        val metadata = value["metadata"]
        if (metadata != null && metadata !is JsonObject) schema("has invalid metadata")
        return JsonlV4Header(
            id = value.string("id") ?: schema("has invalid id"),
            createdAt = requireTimestamp(value["createdAt"]) { schema("has invalid createdAt") },
            parentSessionId = parentSessionId,
            legacyParentSessionPath = legacyParentSessionPath,
            metadata = metadata as JsonObject?,
        )
    }

    /** One mutation line, newline-terminated. */
    fun encodeMutation(mutation: SessionMutation): String = when (mutation) {
        is SessionMutation.Entry ->
            buildJsonObject {
                put("kind", "entry")
                mutation.lane?.let { put("lane", it) }
                putEntry(entry = mutation.entry)
            }.toString() + "\n"
        is SessionMutation.Record ->
            buildJsonObject {
                put("kind", "record")
                putRecordFields(mutation.record)
            }.toString() + "\n"
        is SessionMutation.Lane ->
            buildJsonObject {
                put("kind", "lane")
                put("seq", mutation.seq)
                put("lane", mutation.lane)
                put("leafId", mutation.leafId)
            }.toString() + "\n"
        is SessionMutation.Fact.Name ->
            buildJsonObject {
                put("kind", "fact")
                put("seq", mutation.seq)
                put("fact", "name")
                mutation.name?.let { put("name", it) }
            }.toString() + "\n"
        is SessionMutation.Fact.Label ->
            buildJsonObject {
                put("kind", "fact")
                put("seq", mutation.seq)
                put("fact", "label")
                put("targetId", mutation.targetId)
                mutation.label?.let { put("label", it) }
            }.toString() + "\n"
    }

    fun decodeMutation(line: String): SessionMutation {
        val value = parseObject(line)
        val seq = requireSequence(value["seq"]) { schema("has invalid seq") }
        return when (val kind = value.string("kind")) {
            "entry" -> decodeEntryMutation(value, seq)
            "record" -> SessionMutation.Record(decodeRecordMutation(value, seq))
            "lane" -> SessionMutation.Lane(
                seq = seq,
                lane = value.string("lane") ?: schema("has invalid lane"),
                leafId = requireNullableId(value, "leafId"),
            )
            "fact" -> decodeFactMutation(value, seq)
            else -> schema("has unknown mutation kind $kind")
        }
    }

    private fun decodeEntryMutation(value: JsonObject, seq: Long): SessionMutation.Entry {
        val lane = value.string("lane")
        if ("lane" in value && lane == null) schema("has invalid lane")
        val id = value.string("id") ?: schema("has invalid id")
        val type = value.string("type") ?: schema("has invalid entry type")
        if (type !in ENTRY_TYPES) schema("has unknown entry type $type")
        val parentId = requireNullableId(value, "parentId")
        requireTimestamp(value["timestamp"]) { schema("has invalid timestamp") }
        if (type == "custom" && value.string("customType") == null) schema("has invalid customType")
        // Typed payload decode; the storage-assigned seq comes from the line.
        val entry = decodeEntry(value, seqOverride = seq, defaultParentId = parentId)
        return SessionMutation.Entry(lane, entry)
    }

    private fun decodeRecordMutation(value: JsonObject, seq: Long): LaneRecord {
        val id = value.string("id") ?: schema("has invalid id")
        val lane = value.string("lane") ?: schema("has invalid lane")
        val type = value.string("type") ?: schema("has invalid record type")
        if (type !in RECORD_TYPES) schema("has unknown record type $type")
        val timestamp = requireTimestamp(value["timestamp"]) { schema("has invalid timestamp") }
        val base = RecordLineFields(id, lane, seq, timestamp)
        return when (type) {
            "operation_started" -> {
                val intentElement = value["intent"]
                if (intentElement !is JsonObject) schema("has invalid intent")
                val operationKind = intentElement.string("kind") ?: schema("has invalid operation kind")
                val kind = OperationIntent.Kind.entries.firstOrNull { it.wire == operationKind }
                    ?: schema("has unknown operation kind $operationKind")
                LaneRecord.OperationStartedRecord(
                    id = base.id,
                    lane = base.lane,
                    seq = base.seq,
                    timestamp = base.timestamp,
                    // Absent sourceLeafId decodes as null (pi's codec does not
                    // require it; pi producers always write it).
                    sourceLeafId = if ("sourceLeafId" in value) requireNullableId(value, "sourceLeafId") else null,
                    intent = OperationIntent(kind, intentElement),
                )
            }
            "abort_requested" -> LaneRecord.AbortRequestedRecord(
                id = base.id,
                lane = base.lane,
                seq = base.seq,
                timestamp = base.timestamp,
                runId = value.string("runId") ?: schema("has invalid runId"),
            )
            "operation_finished" -> {
                val outcomeName = value.string("outcome") ?: schema("has invalid outcome")
                val outcome = OperationOutcome.entries.firstOrNull { it.wire == outcomeName }
                    ?: schema("has unknown outcome $outcomeName")
                val error = value.obj("error")?.let { e ->
                    RecordError(
                        code = e.string("code") ?: schema("has invalid error code"),
                        message = e.string("message") ?: schema("has invalid error message"),
                    )
                }
                LaneRecord.OperationFinishedRecord(
                    id = base.id,
                    lane = base.lane,
                    seq = base.seq,
                    timestamp = base.timestamp,
                    runId = value.string("runId") ?: schema("has invalid runId"),
                    outcome = outcome,
                    error = error,
                )
            }
            "usage" -> LaneRecord.UsageRecord(
                id = base.id,
                lane = base.lane,
                seq = base.seq,
                timestamp = base.timestamp,
                usage = decodeUsage(value["usage"]),
                fields = JsonObject(value.filterKeys { it !in RECORD_BASE_FIELDS && it != "kind" && it != "usage" }),
            )
            else -> LaneRecord.DeferredRecord(
                id = base.id,
                lane = base.lane,
                seq = base.seq,
                timestamp = base.timestamp,
                type = type,
                fields = JsonObject(value.filterKeys { it != "kind" && it !in RECORD_BASE_FIELDS }),
            )
        }
    }

    private val RECORD_BASE_FIELDS = setOf("id", "seq", "lane", "timestamp", "type")

    private data class RecordLineFields(val id: String, val lane: String, val seq: Long, val timestamp: Long)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRecordFields(record: LaneRecord) {
        put("id", record.id)
        put("seq", record.seq)
        put("lane", record.lane)
        put("timestamp", record.timestamp)
        when (record) {
            is LaneRecord.OperationStartedRecord -> {
                put("type", "operation_started")
                put("sourceLeafId", record.sourceLeafId)
                put("intent", record.intent.payload)
            }
            is LaneRecord.AbortRequestedRecord -> {
                put("type", "abort_requested")
                put("runId", record.runId)
            }
            is LaneRecord.OperationFinishedRecord -> {
                put("type", "operation_finished")
                put("runId", record.runId)
                put("outcome", record.outcome.wire)
                record.error?.let {
                    putJsonObject("error") {
                        put("code", it.code)
                        put("message", it.message)
                    }
                }
            }
            is LaneRecord.UsageRecord -> {
                put("type", "usage")
                putJsonObject("usage") { putUsage(record.usage) }
                record.fields.forEach { (key, field) -> put(key, field) }
            }
            is LaneRecord.DeferredRecord -> {
                put("type", record.type)
                record.fields.forEach { (key, field) -> put(key, field) }
            }
        }
    }

    private fun decodeFactMutation(value: JsonObject, seq: Long): SessionMutation.Fact = when (value.string("fact")) {
        "name" -> {
            val name = value.string("name")
            if ("name" in value && name == null) schema("has invalid name")
            SessionMutation.Fact.Name(seq, name)
        }
        "label" -> {
            val label = value.string("label")
            if ("label" in value && label == null) schema("has invalid label")
            SessionMutation.Fact.Label(
                seq = seq,
                targetId = value.string("targetId") ?: schema("has invalid targetId"),
                label = label,
            )
        }
        else -> schema("has unknown fact type")
    }

    private fun parseObject(line: String): JsonObject {
        val value = try {
            lenientJson.parseToJsonElement(line)
        } catch (e: Exception) {
            throw JsonlDecodeError(JsonlDecodeError.Kind.SYNTAX, "is not valid JSON", e)
        }
        return value as? JsonObject ?: schema("is not a JSON object")
    }

    private fun requireNullableId(value: JsonObject, field: String): String? {
        val element = value[field] ?: schema("has invalid $field")
        return when {
            element === JsonNull -> null
            else -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: schema("has invalid $field")
        }
    }

    private inline fun requireSequence(value: JsonElement?, invalid: () -> Nothing): Long {
        val seq = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalid()
        if (seq <= 0) invalid()
        return seq
    }

    private inline fun requireTimestamp(value: JsonElement?, invalid: () -> Nothing): Long {
        val ts = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalid()
        if (ts < 0) invalid()
        return ts
    }

    private fun schema(message: String): Nothing =
        throw JsonlDecodeError(JsonlDecodeError.Kind.SCHEMA, message)

    fun kotlinx.serialization.json.JsonObjectBuilder.putEntry(entry: SessionEntry) {
        put("id", entry.id)
        put("seq", entry.seq)
        put("parentId", entry.parentId)
        put("timestamp", entry.timestamp)
        when (entry) {
            is MessageEntry -> {
                put("type", "message")
                // pi's MessageEntry.terminate is `true`-only
                entry.terminate?.takeIf { it }?.let { put("terminate", it) }
                put("message", encodeMessage(entry.message))
            }
            is CompactionEntry -> {
                put("type", "compaction")
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
            is ModelChangeEntry -> {
                put("type", "model_change")
                put("provider", entry.provider)
                put("modelId", entry.modelId)
            }
            is ThinkingLevelEntry -> {
                put("type", "thinking_level_change")
                put("thinkingLevel", entry.thinkingLevel)
            }
            is ActiveToolsEntry -> {
                put("type", "active_tools_change")
                put("activeToolNames", JsonArray(entry.activeToolNames.map(::JsonPrimitive)))
            }
            is BranchSummaryEntry -> {
                put("type", "branch_summary")
                put("fromId", entry.fromId)
                put("summary", entry.summary)
                entry.details?.let { put("details", it) }
                entry.usage?.let { put("usage", buildJsonObject { putUsage(it) }) }
            }
            is CustomEntry -> {
                put("type", "custom")
                put("customType", entry.customType)
                entry.data?.let { put("data", it) }
            }
        }
    }

    fun decodeEntry(element: JsonElement, seqOverride: Long? = null, defaultParentId: String? = null): SessionEntry {
        val obj = element as? JsonObject ?: schema("entry must be an object")
        val id = obj.string("id") ?: schema("entry missing id")
        val seq = seqOverride ?: obj.strictLong("seq") ?: schema("entry missing seq")
        val parentId = obj.string("parentId") ?: defaultParentId
        fun timestamp() = obj.requireLong("timestamp") { schema("entry missing timestamp") }
        return when (val type = obj.string("type")) {
            "message" -> MessageEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                terminate = obj.strictBoolean("terminate"),
                message = decodeMessage(obj["message"] ?: schema("entry missing message")),
            )
            "compaction" -> CompactionEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                summary = obj.string("summary") ?: schema("compaction entry missing summary"),
                retainedTail = (obj.arr("retainedTail") ?: emptyList()).map(::decodeMessage),
                tokensBefore = obj.strictInt("tokensBefore") ?: schema("compaction entry missing tokensBefore"),
                details = obj.obj("details")?.let { d ->
                    CompactionDetails(readFiles = d.stringListOrReject("readFiles"), modifiedFiles = d.stringListOrReject("modifiedFiles"))
                },
                usage = obj["usage"]?.let(::decodeUsage),
            )
            "model_change" -> ModelChangeEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                provider = obj.string("provider") ?: schema("model_change entry missing provider"),
                modelId = obj.string("modelId") ?: schema("model_change entry missing modelId"),
            )
            "thinking_level_change" -> ThinkingLevelEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                thinkingLevel = obj.string("thinkingLevel") ?: schema("thinking_level_change entry missing thinkingLevel"),
            )
            "active_tools_change" -> ActiveToolsEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                activeToolNames = obj.stringListOrReject("activeToolNames"),
            )
            "branch_summary" -> BranchSummaryEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                fromId = obj.string("fromId") ?: schema("branch_summary entry missing fromId"),
                summary = obj.string("summary") ?: schema("branch_summary entry missing summary"),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
            )
            "custom" -> CustomEntry(
                id = id,
                seq = seq,
                parentId = parentId,
                timestamp = timestamp(),
                customType = obj.string("customType") ?: schema("custom entry missing customType"),
                data = obj["data"],
            )
            else -> schema("has unknown entry type $type")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putUsage(usage: works.resolve.pathfinder.ai.Usage) {
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

    internal fun encodeMessage(message: Message): JsonObject = when (message) {
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

    internal fun decodeMessage(element: JsonElement): Message {
        val obj = element as? JsonObject ?: schema("message must be an object")
        return when (val role = obj.string("role")) {
            "user" -> UserMessage(
                content = decodeContentList(obj["content"]),
                timestamp = obj.requireLong("timestamp") { schema("missing message timestamp") },
            )
            "assistant" -> AssistantMessage(
                content = decodeContentList(obj["content"]),
                api = obj.string("api") ?: schema("assistant message missing api"),
                provider = obj.string("provider") ?: schema("assistant message missing provider"),
                model = obj.string("model") ?: schema("assistant message missing model"),
                usage = decodeUsage(obj["usage"]),
                stopReason = decodeStopReason(obj.string("stopReason")),
                errorMessage = obj.string("errorMessage"),
                rawStopReason = obj.string("rawStopReason"),
                responseId = obj.string("responseId"),
                responseModel = obj.string("responseModel"),
                endTurn = obj.strictBoolean("endTurn"),
                timestamp = obj.requireLong("timestamp") { schema("missing message timestamp") },
            )
            "toolResult" -> ToolResultMessage(
                toolCallId = obj.string("toolCallId") ?: schema("tool result missing toolCallId"),
                toolName = obj.string("toolName") ?: schema("tool result missing toolName"),
                content = decodeContentList(obj["content"]),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
                addedToolNames = decodeStringList(obj["addedToolNames"]),
                isError = obj.strictBoolean("isError") ?: schema("tool result missing isError"),
                timestamp = obj.requireLong("timestamp") { schema("missing message timestamp") },
            )
            else -> schema("has unknown message role $role")
        }
    }

    private fun decodeStopReason(name: String?): works.resolve.pathfinder.ai.StopReason =
        name?.let { runCatching { works.resolve.pathfinder.ai.StopReason.valueOf(it) }.getOrNull() }
            ?: schema("has unknown stop reason $name")

    private fun decodeUsage(element: JsonElement?): works.resolve.pathfinder.ai.Usage {
        val obj = element as? JsonObject ?: schema("is missing usage")
        val cost = obj.obj("cost")?.let { c ->
            works.resolve.pathfinder.ai.Cost(
                input = c.requireDouble("input") { schema("is missing cost input") },
                output = c.requireDouble("output") { schema("is missing cost output") },
                cacheRead = c.requireDouble("cacheRead") { schema("is missing cost cacheRead") },
                cacheWrite = c.requireDouble("cacheWrite") { schema("is missing cost cacheWrite") },
                total = c.requireDouble("total") { schema("is missing cost total") },
            )
        } ?: schema("is missing usage cost")
        return works.resolve.pathfinder.ai.Usage(
            input = obj.requireInt("input") { schema("is missing usage input") },
            output = obj.requireInt("output") { schema("is missing usage output") },
            cacheRead = obj.requireInt("cacheRead") { schema("is missing usage cacheRead") },
            cacheWrite = obj.requireInt("cacheWrite") { schema("is missing usage cacheWrite") },
            cacheWrite1h = obj.strictInt("cacheWrite1h") ?: 0,
            reasoning = obj.requireInt("reasoning") { schema("is missing usage reasoning") },
            totalTokens = obj.requireInt("totalTokens") { schema("is missing usage totalTokens") },
            cost = cost,
        )
    }

    private fun encodeContentList(content: List<Content>): JsonArray = JsonArray(content.map(::encodeContent))

    private fun decodeContentList(element: JsonElement?): List<Content> {
        val array = element as? JsonArray ?: schema("is missing content")
        return array.map(::decodeContent)
    }

    private fun JsonObject.stringListOrReject(key: String): List<String> {
        val array = arr(key) ?: schema("has invalid $key")
        return array.map { value -> value.stringOrNull() ?: schema("has invalid $key") }
    }

    private fun decodeStringList(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        val array = element as? JsonArray ?: schema("has invalid addedToolNames")
        return array.map { value -> value.stringOrNull() ?: schema("has invalid addedToolNames") }
    }

    private fun encodeContent(content: Content): JsonObject = when (content) {
        is works.resolve.pathfinder.ai.TextContent -> buildJsonObject {
            put("type", "text")
            put("text", content.text)
            content.textSignature?.let { put("textSignature", it) }
        }
        is works.resolve.pathfinder.ai.ThinkingContent -> buildJsonObject {
            put("type", "thinking")
            put("thinking", content.thinking)
            content.thinkingSignature?.let { put("thinkingSignature", it) }
            if (content.redacted) put("redacted", true)
        }
        is works.resolve.pathfinder.ai.ImageContent -> buildJsonObject {
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
        val obj = element as? JsonObject ?: schema("content must be an object")
        return when (val type = obj.string("type")) {
            "text" -> works.resolve.pathfinder.ai.TextContent(
                text = obj.string("text") ?: schema("text content missing text"),
                textSignature = obj.string("textSignature"),
            )
            "thinking" -> works.resolve.pathfinder.ai.ThinkingContent(
                thinking = obj.string("thinking") ?: schema("thinking content missing thinking"),
                thinkingSignature = obj.string("thinkingSignature"),
                redacted = obj.strictBoolean("redacted") ?: false,
            )
            "image" -> works.resolve.pathfinder.ai.ImageContent(
                data = obj.string("data") ?: schema("image content missing data"),
                mimeType = obj.string("mimeType") ?: schema("image content missing mimeType"),
            )
            "toolCall" -> ToolCall(
                id = obj.string("id") ?: schema("tool call missing id"),
                name = obj.string("name") ?: schema("tool call missing name"),
                arguments = obj.string("arguments") ?: schema("tool call missing arguments"),
                thoughtSignature = obj.string("thoughtSignature"),
                namespace = obj.string("namespace"),
            )
            else -> schema("has unknown content type $type")
        }
    }
}

/**
 * Rejects non-JSON-safe payloads before write. Pathfinder's entries and
 * records are typed values that are JSON-safe by construction, so this
 * walks the encoded mutation's [JsonElement] tree; the only remaining
 * hazard is a non-finite number primitive, which kotlinx would emit as
 * invalid JSON.
 */
internal fun assertJsonSerializable(value: JsonElement) {
    when (value) {
        is JsonPrimitive ->
            if (!value.isString) {
                value.doubleOrNull?.let { double ->
                    if (!double.isFinite()) invalidPayload("contains a non-finite number")
                }
            }
        is JsonArray -> value.forEach(::assertJsonSerializable)
        is JsonObject -> value.forEach { (_, child) -> assertJsonSerializable(child) }
    }
}

private fun invalidPayload(reason: String): Nothing =
    throw SessionError(SessionErrorCode.INVALID_PAYLOAD, "Durable payload $reason")
