package works.resolve.pathfinder.codingagent.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolReference
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.constrainedSamplingToJson
import works.resolve.pathfinder.ai.utils.getCurrentSystemMessage
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.strictDouble
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull
import works.resolve.pathfinder.ai.utils.uuidv7

/** pi's assertValidSessionId: alphanumeric start/end, '-._' allowed inside. */
private val SESSION_ID_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")

private const val INVALID_SESSION_ID_MESSAGE =
    "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
        "and start and end with an alphanumeric character"

fun assertValidSessionId(id: String) {
    if (!SESSION_ID_REGEX.matches(id)) {
        throw SessionError(SessionErrorCode.INVALID_ID, INVALID_SESSION_ID_MESSAGE)
    }
}

/** A node in a session's conversation tree; [parentId] is null for roots. */
sealed class SessionEntry {
    abstract val id: String
    abstract val parentId: String?

    /** Epoch millis. */
    abstract val timestamp: Long
}

/** An entry carrying a chat [message]. */
data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val message: Message
) : SessionEntry()

/**
 * A compaction cut: the summary replacing the compacted history and the id
 * of the first entry kept after it (entries between the previous leaf path
 * root and that id are summarized away). [systemMessage] is the complete
 * prompt and tool state replayed at this boundary; it leads the compacted
 * context in place of the system messages dropped from the kept cut.
 */
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    /** File-operation details of the compacted history. */
    val details: CompactionDetails? = null,
    /** Usage from the LLM call(s) that generated this summary. */
    val usage: Usage? = null,
    val systemMessage: SystemMessage? = null
) : SessionEntry()

/**
 * A recorded model switch: the provider + model that become the branch's
 * effective configuration from this entry onward, folded root→leaf by
 * [getSessionContextSettings].
 */
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val provider: String,
    val modelId: String
) : SessionEntry()

/** A recorded thinking-level switch, folded like [ModelChangeEntry]. */
data class ThinkingLevelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    /** pi's ThinkingLevel wire string, e.g. "off", "high". */
    val thinkingLevel: String
) : SessionEntry()

/** A branch summarization cut: summarizes the branch segment starting at [fromId]. */
data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val fromId: String,
    val summary: String,
    val details: kotlinx.serialization.json.JsonElement? = null,
    /** Usage from the LLM call(s) that generated this summary. */
    val usage: Usage? = null
) : SessionEntry()

/**
 * An entry whose `type` is not a ported kind (pi's usage, label,
 * session_info, custom entries). pi indexes every parsed entry of any type
 * as a raw object; the typed equivalent retains the original JSON,
 * participates in the tree and the unknown-leaf fallback, persists back
 * verbatim, and projects to nothing in the LLM context.
 */
data class RawEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: Long,
    val json: JsonObject
) : SessionEntry()

/** pi's SessionTreeNode; labels are not ported. */
data class SessionTreeNode(val entry: SessionEntry, val children: List<SessionTreeNode>)

/**
 * pi's ReadonlySessionManager (a Pick of SessionManager read methods),
 * reduced to the ported surface; [SessionManager] implements it.
 */
interface ReadonlySessionManager {
    fun getSessionId(): String

    fun getSessionFile(): File?

    fun getEntries(): List<SessionEntry>

    fun getLeafId(): String?

    fun getLeafEntry(): SessionEntry?

    fun getEntry(id: String): SessionEntry?

    fun getBranch(fromId: String? = null): List<SessionEntry>

    fun buildContextEntries(): List<SessionEntry>

    fun buildSessionContext(): SessionContext

    fun getTree(): List<SessionTreeNode>
}

/** pi's getLatestCompactionEntry: the last compaction entry, if any. */
fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is CompactionEntry) return entry
    }
    return null
}

/**
 * pi's buildSessionPath: the active branch's root→leaf path. An unknown
 * leaf falls back to the last entry; only a null leaf is the empty root
 * path.
 */
fun buildSessionPath(
    entries: List<SessionEntry>,
    leafId: String? = entries.lastOrNull()?.id
): List<SessionEntry> {
    if (leafId == null) return emptyList()
    val byId = entries.associateBy { it.id }
    var current: SessionEntry? = byId[leafId] ?: entries.lastOrNull() ?: return emptyList()
    val path = ArrayList<SessionEntry>()
    while (current != null) {
        path.add(current)
        current = current.parentId?.let(byId::get)
    }
    path.reverse()
    return path
}

/** The provider+model pair a branch's configuration selects. */
data class SessionModelSelection(val provider: String, val modelId: String)

/**
 * pi's getSessionContextSettings result (Pick<SessionContext,
 * "thinkingLevel"|"model">): folding the root→leaf path in order,
 * model/thinking-level entries overwrite their field — and assistant
 * messages also update the model, since their provider/model is what
 * actually ran.
 */
data class SessionContextSettings(
    val thinkingLevel: String = "off",
    val model: SessionModelSelection? = null
)

internal fun getSessionContextSettings(path: List<SessionEntry>): SessionContextSettings {
    var thinkingLevel = "off"
    var model: SessionModelSelection? = null
    for (entry in path) {
        when (entry) {
            is ThinkingLevelEntry -> thinkingLevel = entry.thinkingLevel

            is ModelChangeEntry -> model = SessionModelSelection(entry.provider, entry.modelId)

            is MessageEntry -> {
                val assistant = entry.message as? AssistantMessage ?: continue
                model = SessionModelSelection(assistant.provider, assistant.model)
            }

            else -> Unit
        }
    }
    return SessionContextSettings(thinkingLevel, model)
}

/** pi's SessionContext: the resolved LLM message list plus branch settings. */
data class SessionContext(
    val messages: List<Message>,
    val thinkingLevel: String,
    val model: SessionModelSelection?
)

/**
 * pi's sessionEntryToContextMessages: project one entry into LLM messages.
 * Message entries pass through; compaction and branch-summary entries
 * project to their wrapped summary messages; configuration and raw
 * entries project to nothing (pi's plain custom entries are display/state
 * entries and do not participate in context).
 */
fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is MessageEntry -> listOf(entry.message)

    is CompactionEntry -> buildList {
        entry.systemMessage?.let(::add)
        add(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp))
    }

    // Upstream guards summary truthiness; with a non-null summary here, that
    // reduces to excluding the empty string.
    is BranchSummaryEntry -> if (entry.summary.isNotEmpty()) {
        listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
    } else {
        emptyList()
    }

    is ModelChangeEntry, is ThinkingLevelEntry, is RawEntry -> emptyList()
}

/**
 * pi's buildContextEntries over the leaf path: the latest compaction is
 * represented by the compaction entry itself, followed by the path entries
 * from `firstKeptEntryId` through just before the compaction, then
 * everything after the compaction. Older summarized entries are omitted.
 */
fun buildContextEntries(
    entries: List<SessionEntry>,
    leafId: String? = entries.lastOrNull()?.id
): List<SessionEntry> {
    val pathEntries = buildSessionPath(entries, leafId)
    var compaction: CompactionEntry? = null
    for (entry in pathEntries) {
        if (entry is CompactionEntry) compaction = entry
    }
    if (compaction == null) return pathEntries

    val compactionIndex = pathEntries.indexOfFirst { it.id == compaction!!.id }
    if (compactionIndex < 0) return pathEntries

    val contextEntries = ArrayList<SessionEntry>()
    contextEntries.add(compaction!!)
    var foundFirstKept = false
    for (i in 0 until compactionIndex) {
        val entry = pathEntries[i]
        if (entry.id == compaction!!.firstKeptEntryId) foundFirstKept = true
        // System messages among the kept entries are dropped: the compaction
        // entry's systemMessage already carries their replay.
        val isSystemMessage = entry is MessageEntry && entry.message is SystemMessage
        if (foundFirstKept && !isSystemMessage) contextEntries.add(entry)
    }
    contextEntries.addAll(pathEntries.subList(compactionIndex + 1, pathEntries.size))
    return contextEntries
}

/** pi's buildSessionContext: the LLM context for a leaf of the entry tree. */
fun buildSessionContext(
    entries: List<SessionEntry>,
    leafId: String? = entries.lastOrNull()?.id
): SessionContext {
    val settings = getSessionContextSettings(buildSessionPath(entries, leafId))
    val messages = buildContextEntries(entries, leafId).flatMap(::sessionEntryToContextMessages)
    return SessionContext(messages, settings.thinkingLevel, settings.model)
}

/**
 * Read-only session summary for listing, ported from pi's buildSessionInfo.
 * `path` (pi's SessionInfo field) lets a resume open the file directly,
 * with no id discovery. `modified` derives from message timestamps, never
 * file mtime, so merely opening a session cannot reorder the list.
 */
data class SessionInfo(
    val id: String,
    val path: File,
    /** Header timestamp. */
    val createdAt: Long,
    /** Max user/assistant message timestamp (the entry's when the message
     * lacks its own), when positive; else the header timestamp. */
    val modified: Long,
    /** Count of message entries (all roles). */
    val messageCount: Int,
    /** Text of the first user message, or the sentinel "(no messages)". */
    val firstMessage: String,
    /** All user+assistant message texts joined with " ". */
    val allMessagesText: String
)

/**
 * JSONL v3 session codec, pi's session file format: line 0 is the session
 * header, then one entry per line with pi's exact field names. Entry
 * timestamps encode as pi's ISO-8601 UTC strings with exactly three
 * millisecond digits and decode like `new Date(...)` (see [parseIso]);
 * internally entries carry epoch millis. Optional fields encode with the
 * omit-when-null convention (`?.let { put(...) }`): like pi's JSON.stringify
 * of optional fields, absent optionals are omitted, never materialized.
 *
 * Divergences from pi:
 * - `cwd` carries the session's working directory (a connected SSH remote,
 *   annotated app-side) or the empty string when there is none; unlike pi's
 *   constructor-time `process.cwd()`, it is supplied after construction via
 *   [updateCwd] because the SSH connection is established after the manager
 *   exists. Updates land before the header line's lazy first persistence;
 *   the persisted header stays immutable like pi's.
 * - Decode is permissive like pi's parseSessionEntryLine but entry payloads
 *   decode into typed [SessionEntry]s: any line that fails typed decode is
 *   skipped. Unported entry `type`s decode as [RawEntry]s retained verbatim
 *   like pi's raw objects; a raw line must carry a string `id` and a
 *   parseable `timestamp` to index into the typed tree.
 * - Old "v4" files are unreadable (their first line is not a `session`
 *   header) and no migration exists, per AGENTS.md.
 */
internal object JsonlCodec {
    const val SESSION_VERSION = 3

    data class SessionHeader(val id: String, val timestamp: Long, val cwd: String = "")

    /** Header or entry produced by one line. */
    sealed interface Line {
        data class Header(val header: SessionHeader) : Line

        data class Entry(val entry: SessionEntry) : Line
    }

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * ECMA-262 date-time parser for wire timestamps: accepts what
     * `new Date(...)` accepts for the ISO forms — optional seconds,
     * fractional seconds of any length, `Z`/`z` or `±HH:mm`/`±HHmm` zones
     * (zoneless date-times read as local time, date-only as UTC), V8's
     * end-of-month day rollover, and `24:00` midnight — and rejects what it
     * rejects (out-of-range components, hour-only offsets, non-ISO shapes).
     * Fractional digits past milliseconds truncate like `Date.getTime()`.
     */
    private val ECMA_DATE_TIME = Regex(
        """(\d{4})-(\d{2})-(\d{2})(?:[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?([Zz]|[+-]\d{2}:?\d{2})?)?"""
    )

    fun formatIso(millis: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC))

    fun parseIso(value: String): Long? {
        val match = ECMA_DATE_TIME.matchEntire(value) ?: return null
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val hasTime = match.groupValues[4].isNotEmpty()
        val hour = if (hasTime) match.groupValues[4].toInt() else 0
        val minute = if (hasTime) match.groupValues[5].toInt() else 0
        val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val fractionMillis = match.groupValues[7].takeIf { it.isNotEmpty() }
            ?.let { "${it}00".take(3).toInt() } ?: 0
        if (minute !in 0..59 || second !in 0..59) return null
        if (hour == 24) {
            if (minute != 0 || second != 0 || fractionMillis != 0) return null
        } else if (hour > 24) {
            return null
        }
        // V8 rolls day overflow past the month's end instead of rejecting it.
        val date = LocalDate.of(match.groupValues[1].toInt(), month, 1).plusDays((day - 1).toLong())
        val zone = match.groupValues[8]
        // hour 24 (validated to :00:00.000) rolls into the next day.
        val millisOfDay =
            hour * 3_600_000L + minute * 60_000L + second * 1_000L + fractionMillis
        return when {
            zone.equals("Z", ignoreCase = true) ->
                date.toEpochDay() * MILLIS_PER_DAY + millisOfDay

            zone.isNotEmpty() -> {
                val sign = if (zone[0] == '-') -1L else 1L
                val zoneHours = zone.substring(1, 3).toInt()
                val zoneMinutes = zone.substring(3).removePrefix(":").toInt()
                if (zoneHours > 23 || zoneMinutes > 59) return null
                date.toEpochDay() * MILLIS_PER_DAY + millisOfDay -
                    sign * (zoneHours * 3_600_000L + zoneMinutes * 60_000L)
            }

            // Zoneless date-times are local time like JS; date-only is UTC.
            hasTime -> LocalDateTime.of(date, LocalTime.of(hour % 24, minute, second))
                .plusDays((hour / 24).toLong())
                .plusNanos(fractionMillis * 1_000_000L)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            else -> date.toEpochDay() * MILLIS_PER_DAY
        }
    }

    /** pi's file name: the header timestamp with ':'/'.' replaced, then the session id. */
    fun sessionFileName(headerTimestampMillis: Long, sessionId: String): String =
        formatIso(headerTimestampMillis).replace(":", "-").replace(".", "-") + "_$sessionId.jsonl"

    fun encodeHeaderLine(header: SessionHeader): String = buildJsonObject {
        put("type", "session")
        put("version", SESSION_VERSION)
        put("id", header.id)
        put("timestamp", formatIso(header.timestamp))
        put("cwd", header.cwd)
    }.toString() + "\n"

    fun encodeEntryLine(entry: SessionEntry): String {
        // Raw entries re-serialize their original object verbatim.
        if (entry is RawEntry) return entry.json.toString() + "\n"
        return buildJsonObject {
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
                    entry.systemMessage?.let { put("systemMessage", encodeMessage(it)) }
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
    }

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
                    val cwd = obj.string("cwd") ?: ""
                    Line.Header(SessionHeader(id, timestamp, cwd))
                }

                "message", "compaction", "model_change", "thinking_level_change",
                "branch_summary" -> Line.Entry(decodeEntry(obj))

                else -> Line.Entry(decodeRawEntry(obj))
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
                message = decodeMessage(obj["message"] ?: invalid(), timestamp)
            )

            "compaction" -> CompactionEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                summary = obj.string("summary") ?: invalid(),
                firstKeptEntryId = obj.string("firstKeptEntryId") ?: invalid(),
                tokensBefore = obj.strictDouble("tokensBefore")?.toInt() ?: invalid(),
                details = decodeDetails(obj["details"]),
                usage = obj["usage"]?.let(::decodeUsage),
                systemMessage = obj["systemMessage"]?.let {
                    decodeMessage(it, timestamp) as? SystemMessage ?: invalid()
                }
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

    /**
     * Any other well-formed entry object, pi's raw retention: only the tree
     * keys are typed; the rest of the line stays opaque.
     */
    private fun decodeRawEntry(obj: JsonObject): SessionEntry {
        val id = obj.string("id") ?: invalid()
        val parentId = (obj["parentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val timestamp = obj.string("timestamp")?.let(::parseIso) ?: invalid()
        return RawEntry(id, parentId, timestamp, obj)
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

    // ---- message codecs (unchanged wire shape from the v4 codec) ----

    fun encodeMessage(message: Message): JsonObject = when (message) {
        is UserMessage -> buildJsonObject {
            put("role", "user")
            put("timestamp", message.timestamp)
            put("content", encodeContentList(message.content))
        }

        is SystemMessage -> buildJsonObject {
            put("role", "system")
            put("timestamp", message.timestamp)
            // pi's wire content is a plain string, or a content array when a
            // message carries structured blocks.
            put(
                "content",
                when {
                    message.content.isEmpty() -> JsonPrimitive("")

                    message.content.size == 1 && message.content[0] is TextContent ->
                        JsonPrimitive((message.content[0] as TextContent).text)

                    else -> encodeContentList(message.content)
                }
            )
            message.sections?.let { sections ->
                putJsonObject("sections") {
                    sections.forEach { (name, value) ->
                        put(name, if (value == null) JsonNull else JsonPrimitive(value))
                    }
                }
            }
            message.toolsAdded?.takeIf { it.isNotEmpty() }?.let { tools ->
                put("toolsAdded", JsonArray(tools.map(::encodeTool)))
            }
            message.toolsRemoved?.takeIf { it.isNotEmpty() }?.let { tools ->
                put(
                    "toolsRemoved",
                    JsonArray(tools.map { buildJsonObject { put("name", it.name) } })
                )
            }
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "assistant")
            put("timestamp", message.timestamp)
            put("content", encodeContentList(message.content))
            put("api", message.api)
            put("provider", message.provider)
            put("model", message.model)
            putJsonObject("usage") { putUsage(message.usage) }
            put("stopReason", encodeStopReason(message.stopReason))
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
        }
    }

    /** pi's StopReason wire strings (types.ts union): camelCase. */
    private fun encodeStopReason(reason: StopReason): String = when (reason) {
        StopReason.PENDING -> "pending"
        StopReason.STOP -> "stop"
        StopReason.LENGTH -> "length"
        StopReason.TOOL_USE -> "toolUse"
        StopReason.ERROR -> "error"
        StopReason.ABORTED -> "aborted"
        StopReason.DEFERRED -> "deferred"
    }

    private fun decodeStopReason(value: String): StopReason? = when (value) {
        "pending" -> StopReason.PENDING
        "stop" -> StopReason.STOP
        "length" -> StopReason.LENGTH
        "toolUse" -> StopReason.TOOL_USE
        "error" -> StopReason.ERROR
        "aborted" -> StopReason.ABORTED
        "deferred" -> StopReason.DEFERRED
        else -> null
    }

    /**
     * A message lacking its own `timestamp` decodes with the entry's: pi
     * keeps it absent and falls back to the entry timestamp only when
     * computing session activity; the typed message cannot be absent, so
     * the entry timestamp is stamped here — the same instant pi's fallback
     * resolves to.
     */
    fun decodeMessage(
        element: kotlinx.serialization.json.JsonElement,
        entryTimestamp: Long
    ): Message {
        val obj = element as? JsonObject ?: invalid()
        return when (val role = obj.string("role")) {
            "system" -> SystemMessage(
                content = decodeSystemContent(obj["content"]),
                sections = obj["sections"]?.let(::decodeSections),
                toolsAdded = obj["toolsAdded"]?.let(::decodeTools),
                toolsRemoved = obj["toolsRemoved"]?.let { element ->
                    (element as? JsonArray)
                        ?.map { ToolReference((it as JsonObject).string("name") ?: invalid()) }
                        ?: invalid()
                },
                timestamp = obj.strictDouble("timestamp")?.toLong() ?: entryTimestamp
            )

            "user" -> UserMessage(
                content = decodeContentList(obj["content"]),
                timestamp = obj.strictDouble("timestamp")?.toLong() ?: entryTimestamp
            )

            "assistant" -> AssistantMessage(
                content = decodeContentList(obj["content"]),
                api = obj.string("api") ?: invalid(),
                provider = obj.string("provider") ?: invalid(),
                model = obj.string("model") ?: invalid(),
                usage = decodeUsage(obj["usage"] ?: invalid()),
                stopReason = obj.string("stopReason")?.let(::decodeStopReason) ?: invalid(),
                errorMessage = obj.string("errorMessage"),
                rawStopReason = obj.string("rawStopReason"),
                responseId = obj.string("responseId"),
                responseModel = obj.string("responseModel"),
                endTurn = obj["endTurn"]?.let { (it as JsonPrimitive).content.toBooleanStrict() },
                timestamp = obj.strictDouble("timestamp")?.toLong() ?: entryTimestamp
            )

            "toolResult" -> ToolResultMessage(
                toolCallId = obj.string("toolCallId") ?: invalid(),
                toolName = obj.string("toolName") ?: invalid(),
                content = decodeContentList(obj["content"]),
                details = obj["details"],
                usage = obj["usage"]?.let(::decodeUsage),
                isError =
                    obj["isError"]?.let { (it as JsonPrimitive).content.toBooleanStrict() }
                        ?: invalid(),
                timestamp = obj.strictDouble("timestamp")?.toLong() ?: entryTimestamp
            )

            else -> invalid("unknown message role $role")
        }
    }

    /** pi's system-message content: a plain string (empty for announcements) or a text-content array. */
    private fun decodeSystemContent(
        element: kotlinx.serialization.json.JsonElement?
    ): List<TextContent> = when (element) {
        // pi normalizes a null/missing content to "".
        null, JsonNull -> emptyList()

        is JsonPrimitive ->
            element.contentOrNull?.takeIf { it.isNotEmpty() }
                ?.let { listOf(TextContent(it)) }
                ?: emptyList()

        is JsonArray -> decodeContentList(element).map {
            it as? TextContent ?: invalid()
        }

        else -> invalid()
    }

    /** Named prompt sections; an explicit null removes the section. */
    private fun decodeSections(
        element: kotlinx.serialization.json.JsonElement
    ): Map<String, String?> {
        val obj = element as? JsonObject ?: invalid()
        return obj.mapValues { (_, value) ->
            when (value) {
                is JsonNull -> null
                is JsonPrimitive -> value.content
                else -> invalid()
            }
        }
    }

    private fun encodeTool(tool: Tool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", tool.parameters)
        tool.constrainedSampling?.let { put("constrainedSampling", constrainedSamplingToJson(it)) }
    }

    private fun decodeTools(element: kotlinx.serialization.json.JsonElement): List<Tool> {
        val array = element as? JsonArray ?: invalid()
        return array.map { item ->
            val obj = item as? JsonObject ?: invalid()
            Tool(
                name = obj.string("name") ?: invalid(),
                description = obj.string("description") ?: invalid(),
                parameters = obj["parameters"] ?: invalid(),
                constrainedSampling = obj["constrainedSampling"]?.let(::decodeConstrainedSampling)
            )
        }
    }

    private fun decodeConstrainedSampling(
        element: kotlinx.serialization.json.JsonElement
    ): ConstrainedSamplingConfig {
        if (element is JsonPrimitive && !element.isString && element.content == "false") {
            return ConstrainedSamplingConfig.Disabled
        }
        val obj = element as? JsonObject ?: invalid()
        return when (obj.string("type")) {
            "json_schema" -> ConstrainedSamplingConfig.JsonSchema(
                when (obj.string("strict")) {
                    "prefer" -> StrictJsonSchemaMode.PREFER
                    "require" -> StrictJsonSchemaMode.REQUIRE
                    else -> invalid()
                }
            )

            "grammar" -> {
                val variants = obj["variants"] as? JsonObject ?: invalid()
                ConstrainedSamplingConfig.Grammar(
                    variants.entries.associate { (format, definition) ->
                        val key = when (format) {
                            "openai_lark" -> GrammarFormat.OPENAI_LARK
                            "openai_regex" -> GrammarFormat.OPENAI_REGEX
                            else -> invalid()
                        }
                        key to (definition.stringOrNull() ?: invalid())
                    }
                )
            }

            else -> invalid()
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
        val c = { key: String -> cost.strictDouble(key) ?: invalid() }
        fun i(key: String) = obj.strictDouble(key)?.toInt() ?: invalid()
        return Usage(
            input = i("input"),
            output = i("output"),
            cacheRead = i("cacheRead"),
            cacheWrite = i("cacheWrite"),
            cacheWrite1h = obj.strictDouble("cacheWrite1h")?.toInt() ?: 0,
            // pi's `reasoning` is optional: written whenever the provider reported
            // one (explicit 0 included), omitted only when undefined. The typed
            // Usage folds undefined into 0, so the codec omits zero and absence
            // here decodes back to 0.
            reasoning = obj.strictDouble("reasoning")?.toInt() ?: 0,
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
        // pi normalizes null content to [] at read time (old versions, forks,
        // and hand-edited files contain it); here that happens at decode so
        // the entry survives with empty content. Non-null non-array content
        // stays invalid — the typed port cannot represent it.
        if (element == null || element is JsonNull) return emptyList()
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
                arguments = obj.obj("arguments") ?: invalid(),
                thoughtSignature = obj.string("thoughtSignature"),
                namespace = obj.string("namespace")
            )

            else -> invalid("unknown content type $type")
        }
    }
}

/**
 * Owns a session's entry tree and its JSONL persistence, ported from pi's
 * classic SessionManager.
 *
 * Persistence contract (pi's `_persist`): a session file is created lazily
 * — entries are buffered in memory until the first assistant message is
 * appended, at which point the full buffered prefix (header + every entry)
 * is written to a file created exclusively; later appends append one line.
 * This makes empty new sessions non-durable by construction and guarantees
 * every file on disk contains an assistant message, so loading a session
 * never re-triggers the new-session seed path.
 *
 * Concurrency divergence from pi: pi relies on JS single-threadedness;
 * here every public suspend operation is serialized through an internal
 * [Mutex] and file IO runs on [ioDispatcher], while reads capture an
 * immutable published snapshot lock-free. Like pi's `_appendEntry`, an
 * append commits in-memory state first and persists second, so a storage
 * failure throws [SessionError] with code [SessionErrorCode.STORAGE]
 * while the entry stays committed in memory.
 */
class SessionManager private constructor(
    private val dir: File,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    private val entryIdFactory: () -> String,
    @Volatile
    private var header: JsonlCodec.SessionHeader,
    private val sessionFile: File?,
    initialFlushed: Boolean
) : ReadonlySessionManager {
    private val mutex = Mutex()

    private val sessionId: String = header.id

    /**
     * Immutable (entries, leafId) snapshot swapped under [mutex] on every
     * mutation; read methods capture it once so no read observes a
     * half-applied append.
     */
    private class Snapshot(val entries: List<SessionEntry>, val leafId: String?)

    @Volatile
    private var state: Snapshot

    private val byId = LinkedHashMap<String, SessionEntry>()
    private var leafIdLocked: String? = null
    private var flushed = initialFlushed

    // byId is append-only (branching moves the leaf, never removes entries),
    // so a monotonic flag cannot drift from the true assistant presence.
    private var hasAssistantLocked = false

    init {
        state = Snapshot(emptyList(), null)
    }

    private fun rebuildSnapshot() {
        state = Snapshot(byId.values.toList(), leafIdLocked)
    }

    /**
     * pi's generateId: 8 lowercase hex chars, collision-checked against the
     * entry index; after 100 collisions falls back to a full uuid.
     */
    private fun generateId(): String {
        for (i in 0 until 100) {
            val id = entryIdFactory()
            if (!byId.containsKey(id)) return id
        }
        return uuidv7()
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    /**
     * pi fixes the header cwd at construction from process.cwd(); Android
     * has none, and the session's cwd (the SSH remote) is only known once
     * the app factory has connected. [writeFullFileLocked] reads [header]
     * under the persistence mutex, so a value set before the lazy first
     * flush lands in the persisted header line; a later update cannot
     * rewrite an already-persisted header, matching pi's immutable header.
     */
    fun updateCwd(cwd: String) {
        header = header.copy(cwd = cwd)
    }

    private fun encodeLine(entry: SessionEntry): String = JsonlCodec.encodeEntryLine(entry)

    /** pi's openSync "wx" for the lazy creation; truncation only rewrites an adopted empty file. */
    private fun writeFullFileLocked(exclusive: Boolean) {
        try {
            dir.mkdirs()
            val options = if (exclusive) {
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } else {
                setOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            }
            Files.newByteChannel(sessionFile!!.toPath(), options).use { channel ->
                val out = StringBuilder(JsonlCodec.encodeHeaderLine(header))
                for (entry in byId.values) out.append(encodeLine(entry))
                channel.write(ByteBuffer.wrap(out.toString().toByteArray(StandardCharsets.UTF_8)))
            }
        } catch (e: Exception) {
            throw SessionError(SessionErrorCode.STORAGE, "Failed to create session file", e)
        }
    }

    private fun appendLineLocked(line: String) {
        try {
            Files.write(
                sessionFile!!.toPath(),
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            throw SessionError(SessionErrorCode.STORAGE, "Failed to append session entry", e)
        }
    }

    /** pi's _persist, see class KDoc for the lazy-creation contract. */
    private fun persistLocked(entry: SessionEntry) {
        if (!hasAssistantLocked) {
            if (flushed) appendLineLocked(encodeLine(entry))
            return
        }
        if (!flushed) {
            writeFullFileLocked(exclusive = true)
            flushed = true
        } else {
            appendLineLocked(encodeLine(entry))
        }
    }

    /** pi's _appendEntry: commit in memory, then persist. */
    private suspend fun appendEntry(
        build: (id: String, parentId: String?, timestamp: Long) -> SessionEntry
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val entry = build(generateId(), leafIdLocked, now())
            byId[entry.id] = entry
            if (entry is MessageEntry && entry.message is AssistantMessage) {
                hasAssistantLocked = true
            }
            leafIdLocked = entry.id
            rebuildSnapshot()
            persistLocked(entry)
        }
    }

    /** Append a message as a child of the current leaf, then advance the leaf. */
    suspend fun appendMessage(message: Message) {
        appendEntry { id, parentId, timestamp ->
            MessageEntry(id, parentId, timestamp, message)
        }
    }

    suspend fun appendModelChange(provider: String, modelId: String) {
        appendEntry { id, parentId, timestamp ->
            ModelChangeEntry(id, parentId, timestamp, provider, modelId)
        }
    }

    suspend fun appendThinkingLevelChange(thinkingLevel: String) {
        appendEntry { id, parentId, timestamp ->
            ThinkingLevelEntry(id, parentId, timestamp, thinkingLevel)
        }
    }

    suspend fun appendCompaction(
        summary: String,
        firstKeptEntryId: String,
        tokensBefore: Int,
        details: CompactionDetails?,
        usage: Usage?
    ) {
        appendEntry { id, parentId, timestamp ->
            CompactionEntry(
                id,
                parentId,
                timestamp,
                summary,
                firstKeptEntryId,
                tokensBefore,
                details,
                usage,
                systemMessage = getCurrentSystemMessage(buildSessionContext().messages)
                    ?.copy(timestamp = timestamp)
            )
        }
    }

    /** Move the leaf to [branchFromId]; the next append starts a new branch there. */
    suspend fun branch(branchFromId: String) {
        mutex.withLock {
            if (!byId.containsKey(branchFromId)) {
                throw SessionError(SessionErrorCode.NOT_FOUND, "Entry $branchFromId not found")
            }
            leafIdLocked = branchFromId
            rebuildSnapshot()
        }
    }

    /** Clear the leaf; the next append creates a new root entry. */
    suspend fun resetLeaf() {
        mutex.withLock {
            leafIdLocked = null
            rebuildSnapshot()
        }
    }

    /**
     * Branch from [branchFromId] while appending a summary of the abandoned
     * path: the summary entry's `fromId` records the old leaf (or "root"),
     * the leaf moves to [branchFromId], and the entry is appended as its
     * child. Returns the entry id.
     */
    suspend fun branchWithSummary(
        branchFromId: String?,
        summary: String,
        details: kotlinx.serialization.json.JsonElement?,
        usage: Usage?
    ): String = withContext(ioDispatcher) {
        mutex.withLock {
            if (branchFromId != null && !byId.containsKey(branchFromId)) {
                throw SessionError(SessionErrorCode.NOT_FOUND, "Entry $branchFromId not found")
            }
            val fromId = leafIdLocked ?: "root"
            leafIdLocked = branchFromId
            val entry = BranchSummaryEntry(
                id = generateId(),
                parentId = branchFromId,
                timestamp = now(),
                fromId = fromId,
                summary = summary,
                details = details,
                usage = usage
            )
            byId[entry.id] = entry
            leafIdLocked = entry.id
            rebuildSnapshot()
            persistLocked(entry)
            entry.id
        }
    }

    // =========================================================================
    // Reads (pi's tree traversal and context projection)
    // =========================================================================

    override fun getSessionId(): String = sessionId

    override fun getSessionFile(): File? = sessionFile

    override fun getEntries(): List<SessionEntry> = state.entries

    override fun getLeafId(): String? = state.leafId

    override fun getLeafEntry(): SessionEntry? {
        val snapshot = state
        val leaf = snapshot.leafId ?: return null
        return snapshot.entries.firstOrNull { it.id == leaf }
    }

    override fun getEntry(id: String): SessionEntry? = state.entries.firstOrNull { it.id == id }

    /**
     * pi's getBranch: walk from [fromId] (default the current leaf) to the
     * root and return the path root-first. Includes all entry types.
     */
    override fun getBranch(fromId: String?): List<SessionEntry> {
        val snapshot = state
        val byId = HashMap<String, SessionEntry>(snapshot.entries.size)
        for (entry in snapshot.entries) byId[entry.id] = entry
        val path = ArrayList<SessionEntry>()
        var current = (fromId ?: snapshot.leafId)?.let(byId::get)
        while (current != null) {
            path.add(current)
            current = current.parentId?.let(byId::get)
        }
        path.reverse()
        return path
    }

    /**
     * Build the active, compaction-aware entry list for context/rendering,
     * from the current leaf.
     */
    override fun buildContextEntries(): List<SessionEntry> {
        val snapshot = state
        return buildContextEntries(snapshot.entries, snapshot.leafId)
    }

    /** Build the session context (what gets sent to the LLM) from the current leaf. */
    override fun buildSessionContext(): SessionContext {
        val snapshot = state
        return buildSessionContext(snapshot.entries, snapshot.leafId)
    }

    /**
     * Get the session as a tree structure. A well-formed session has
     * exactly one root (first entry with parentId === null); orphaned
     * entries (broken parent chain) are also returned as roots. Built
     * iteratively rather than by recursion: a linear session makes the
     * tree as deep as the transcript, and pi documents the stack overflow
     * recursion causes on deep trees.
     */
    override fun getTree(): List<SessionTreeNode> {
        val entries = state.entries
        val byId = entries.associateBy { it.id }

        fun isRoot(entry: SessionEntry): Boolean {
            val pid = entry.parentId
            return pid == null || pid == entry.id || byId[pid] == null
        }

        val childrenOf = HashMap<String?, MutableList<SessionEntry>>()
        for (entry in entries) {
            if (!isRoot(entry)) childrenOf.getOrPut(entry.parentId!!) { mutableListOf() } += entry
        }

        val pending = childrenOf.mapValues { it.value.size }.toMutableMap()
        val subtrees = HashMap<String, SessionTreeNode>()
        val rootNodes = ArrayList<SessionTreeNode>()
        val stack = ArrayDeque(entries.filter { (pending[it.id] ?: 0) == 0 })
        while (stack.isNotEmpty()) {
            val entry = stack.removeLast()
            val children = (childrenOf[entry.id] ?: emptyList())
                .mapNotNull { subtrees[it.id] }
                .sortedBy { it.entry.timestamp }
            val node = SessionTreeNode(entry, children)
            if (isRoot(entry)) {
                rootNodes += node
            } else {
                val pid = entry.parentId!!
                subtrees[entry.id] = node
                val remaining = (pending[pid] ?: 0) - 1
                pending[pid] = remaining
                if (remaining == 0) stack.addLast(byId.getValue(pid))
            }
        }
        // pi pushes roots in file order; only children are timestamp-sorted.
        val entryIndex = entries.withIndex().associate { (i, e) -> e.id to i }
        return rootNodes.sortedBy { entryIndex[it.entry.id] ?: 0 }
    }

    companion object {
        private val secureRandom = SecureRandom()

        private fun defaultEntryId(): String {
            val bytes = ByteArray(4)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * New session: memory only. Nothing is written until the first
         * assistant message commits (see class KDoc).
         */
        suspend fun create(
            dir: File,
            clock: Clock = Clock.System,
            idFactory: () -> String = ::uuidv7,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            entryIdFactory: () -> String = ::defaultEntryId
        ): SessionManager {
            val id = idFactory()
            assertValidSessionId(id)
            val timestamp = clock.now().toEpochMilliseconds()
            val header = JsonlCodec.SessionHeader(id, timestamp)
            val file = File(dir, JsonlCodec.sessionFileName(timestamp, id))
            return SessionManager(dir, clock, ioDispatcher, entryIdFactory, header, file, false)
        }

        /** Loaded file state: header plus entries. */
        private data class LoadedFile(
            val header: JsonlCodec.SessionHeader,
            val entries: List<SessionEntry>
        )

        /**
         * pi's loadEntriesFromFile: skip blank/malformed lines; the first
         * valid line must be the `session` header (otherwise the file is
         * not a session — including unreadable old "v4" files); the final
         * unterminated line still parses and a non-empty trailing fragment
         * signals a torn tail the caller may repair by appending the
         * missing newline.
         */
        private fun loadFile(file: File): Pair<LoadedFile?, Boolean> {
            if (!file.exists()) return null to false
            val text = file.readText(StandardCharsets.UTF_8)
            var tornTail = false
            if (!text.endsWith("\n")) {
                val lastNewline = text.lastIndexOf('\n')
                val pending = if (lastNewline == -1) text else text.substring(lastNewline + 1)
                if (pending.isNotEmpty()) tornTail = true
            }
            var header: JsonlCodec.SessionHeader? = null
            val entries = ArrayList<SessionEntry>()
            // pi splits on \n only; a stray \r inside a line is JSON
            // whitespace, not a line break — a \r-joined pair is one
            // unparsable line, skipped whole.
            for (line in text.split('\n')) {
                val parsed = JsonlCodec.parseLine(line) ?: continue
                if (header == null) {
                    // Header-first validation: a non-header first valid
                    // line makes the whole file invalid.
                    if (parsed !is JsonlCodec.Line.Header) return null to false
                    header = parsed.header
                } else {
                    when (parsed) {
                        is JsonlCodec.Line.Header -> Unit
                        is JsonlCodec.Line.Entry -> entries.add(parsed.entry)
                    }
                }
            }
            return header?.let { LoadedFile(it, entries) } to tornTail
        }

        /**
         * Open a session file, repairing a torn tail. A missing file is a
         * fresh in-memory session at that path (pi's _setSessionFile:
         * nothing is written until the first assistant commits); an empty
         * existing file is initialized with a header immediately; a
         * non-empty file that does not parse as a session is rejected.
         */
        suspend fun open(
            file: File,
            clock: Clock = Clock.System,
            idFactory: () -> String = ::uuidv7,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            entryIdFactory: () -> String = ::defaultEntryId
        ): SessionManager = withContext(ioDispatcher) {
            val fileExists = file.exists()
            val (loaded, tornTail) = loadFile(file)
            if (loaded == null && fileExists && file.length() > 0) {
                throw SessionError(
                    SessionErrorCode.INVALID_ENTRY,
                    "Session file is not a valid session: $file"
                )
            }
            // Repair only after the file proved to be a valid session (pi
            // validates the header before repairing).
            if (loaded != null && tornTail) {
                try {
                    file.appendText("\n", StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    throw SessionError(SessionErrorCode.STORAGE, "Failed to repair session file", e)
                }
            }
            val initializingEmpty = loaded == null && fileExists
            val manager = if (loaded == null) {
                val id = idFactory()
                assertValidSessionId(id)
                SessionManager(
                    file.parentFile,
                    clock,
                    ioDispatcher,
                    entryIdFactory,
                    JsonlCodec.SessionHeader(id, clock.now().toEpochMilliseconds()),
                    file,
                    initialFlushed = initializingEmpty
                )
            } else {
                SessionManager(
                    file.parentFile,
                    clock,
                    ioDispatcher,
                    entryIdFactory,
                    loaded.header,
                    file,
                    initialFlushed = true
                )
            }
            for (entry in loaded?.entries ?: emptyList()) {
                manager.byId[entry.id] = entry
                manager.leafIdLocked = entry.id
                if (entry is MessageEntry && entry.message is AssistantMessage) {
                    manager.hasAssistantLocked = true
                }
            }
            manager.rebuildSnapshot()
            if (initializingEmpty) manager.writeFullFileLocked(exclusive = false)
            manager
        }

        /**
         * List sessions sorted by `modified` descending. Unparseable files
         * are skipped; one corrupt file must not hide the others. Loads run
         * with pi's bounded concurrency over name-descending file order, and
         * ties in `modified` keep that load order (pi's stable sort over the
         * same ordering).
         */
        suspend fun list(
            dir: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): List<SessionInfo> {
            val files = withContext(ioDispatcher) {
                dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
            } ?: return emptyList()
            val semaphore = Semaphore(MAX_CONCURRENT_SESSION_INFO_LOADS)
            val infos = coroutineScope {
                files.sortedWith(compareByDescending { it.name })
                    .map { file ->
                        async {
                            semaphore.withPermit { buildSessionInfo(file, ioDispatcher) }
                        }
                    }
                    .awaitAll()
            }
            return infos.filterNotNull().sortedByDescending { it.modified }
        }

        /**
         * pi's buildSessionInfo: one streamed pass over the file, decoding
         * line by line. Unlike [loadFile], never materializes the whole
         * file; a file whose first valid line is not the header, or that
         * fails to read, reads as null so one bad file cannot hide the
         * others.
         */
        suspend fun buildSessionInfo(
            file: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): SessionInfo? = withContext(ioDispatcher) {
            try {
                file.bufferedReader(StandardCharsets.UTF_8).useLines {
                    buildSessionInfoFromLines(it, file)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun buildSessionInfoFromLines(lines: Sequence<String>, file: File): SessionInfo? {
            var header: JsonlCodec.SessionHeader? = null
            var messageCount = 0
            var firstMessage: String? = null
            var lastActivity: Long? = null
            val allMessages = ArrayList<String>()
            for (line in lines) {
                val parsed = JsonlCodec.parseLine(line) ?: continue
                if (header == null) {
                    if (parsed !is JsonlCodec.Line.Header) return null
                    header = parsed.header
                    continue
                }
                if (parsed !is JsonlCodec.Line.Entry) continue
                val entry = parsed.entry
                if (entry !is MessageEntry) continue
                messageCount++
                val message = entry.message
                if (message !is UserMessage && message !is AssistantMessage) {
                    continue
                }
                lastActivity = maxOf(lastActivity ?: 0L, message.timestamp)
                val content = when (message) {
                    is UserMessage -> message.content
                    is AssistantMessage -> message.content
                    else -> emptyList()
                }
                val text = content.filterIsInstance<TextContent>()
                    .joinToString(" ") { it.text }
                if (text.isEmpty()) continue
                allMessages.add(text)
                if (firstMessage == null && message is UserMessage) {
                    firstMessage = text
                }
            }
            val sessionHeader = header ?: return null
            // pi falls back to the file mtime when the header timestamp is
            // unreadable; that is unreachable here — the header decode
            // requires a parseable timestamp — so modified bottoms out at
            // the header time.
            return SessionInfo(
                id = sessionHeader.id,
                path = file,
                createdAt = sessionHeader.timestamp,
                modified = lastActivity?.takeIf { it > 0 } ?: sessionHeader.timestamp,
                messageCount = messageCount,
                firstMessage = firstMessage ?: "(no messages)",
                allMessagesText = allMessages.joinToString(" ")
            )
        }

        private const val SESSION_HEADER_READ_BUFFER_SIZE = 4096
        private const val MAX_SESSION_HEADER_SCAN_BYTES = 1024 * 1024
        private const val NEWLINE_BYTE: Byte = 10

        /** pi's MAX_CONCURRENT_SESSION_INFO_LOADS. */
        private const val MAX_CONCURRENT_SESSION_INFO_LOADS = 10

        /**
         * Bounded scan for the session header: blank and malformed lines
         * are skipped, and the first parsed line must be the session header
         * — any other first valid line means the file is not a session.
         * Reads at most [MAX_SESSION_HEADER_SCAN_BYTES]. Divergence from
         * pi: it throws past that bound so its caller can fall back to a
         * full load, but no consumer here needs the distinction, so a
         * past-bound file, like an unreadable one, reads as null.
         */
        private fun readSessionHeader(file: File): JsonlCodec.SessionHeader? = try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(SESSION_HEADER_READ_BUFFER_SIZE)
                val pending = ByteArrayOutputStream()
                var scannedBytes = 0
                while (scannedBytes < MAX_SESSION_HEADER_SCAN_BYTES) {
                    val readLength =
                        minOf(buffer.size, MAX_SESSION_HEADER_SCAN_BYTES - scannedBytes)
                    val bytesRead = input.read(buffer, 0, readLength)
                    if (bytesRead == -1) return@use pendingLineHeader(pending)
                    scannedBytes += bytesRead
                    for (i in 0 until bytesRead) {
                        if (buffer[i] != NEWLINE_BYTE) {
                            pending.write(buffer[i].toInt())
                            continue
                        }
                        when (val candidate = parseSessionHeaderCandidate(pendingLine(pending))) {
                            is SessionHeaderCandidate.Header -> return candidate.header
                            SessionHeaderCandidate.NotASession -> return null
                            null -> Unit
                        }
                        pending.reset()
                    }
                }
                // pi probes one byte past the bound: a final unterminated
                // header still counts when the file ends exactly there.
                if (input.read() != -1) return@use null
                pendingLineHeader(pending)
            }
        } catch (_: Exception) {
            null
        }

        private sealed interface SessionHeaderCandidate {
            data object NotASession : SessionHeaderCandidate

            data class Header(val header: JsonlCodec.SessionHeader) : SessionHeaderCandidate
        }

        /**
         * pi's parseSessionHeaderCandidate, tri-state over one physical
         * line: null keeps scanning (blank or non-JSON), NotASession rejects
         * the file (a parsed line that is not a session header), Header
         * accepts. Divergence: pi accepts a session line on type and id
         * alone even when the rest fails to decode; here a session line
         * that fails the codec's strict decode (e.g. a junk timestamp)
         * also rejects the file, so discovery agrees with [open].
         */
        private fun parseSessionHeaderCandidate(line: String): SessionHeaderCandidate? {
            if (line.isBlank()) return null
            val element = try {
                Json.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
            val obj = element as? JsonObject
                ?: return SessionHeaderCandidate.NotASession
            if (obj.string("type") != "session" || obj.string("id") == null) {
                return SessionHeaderCandidate.NotASession
            }
            return (JsonlCodec.parseLine(line) as? JsonlCodec.Line.Header)
                ?.let { SessionHeaderCandidate.Header(it.header) }
                ?: SessionHeaderCandidate.NotASession
        }

        // Lines split on the byte level so multibyte sequences never break
        // across chunks; decode replaces dangling tails like pi's StringDecoder.
        private fun pendingLine(pending: ByteArrayOutputStream): String =
            String(pending.toByteArray(), StandardCharsets.UTF_8)

        private fun pendingLineHeader(pending: ByteArrayOutputStream): JsonlCodec.SessionHeader? = (
            parseSessionHeaderCandidate(pendingLine(pending))
                as? SessionHeaderCandidate.Header
            )?.header

        /**
         * pi's findMostRecentSession: the session file with the newest
         * [File.lastModified] in [dir], or null when none. Candidates are
         * ordered by mtime so the first file with a valid session header
         * wins — headers are read newest-first, not for every file.
         * Divergence: pi optionally filters sessions by header cwd;
         * sessions here share one unscoped directory, so that filter is
         * omitted.
         */
        suspend fun findMostRecentSession(
            dir: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): File? = withContext(ioDispatcher) {
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
                ?: return@withContext null
            files.sortedByDescending { it.lastModified() }.firstOrNull {
                readSessionHeader(it) !=
                    null
            }
        }

        /** The session file whose header id equals [id], or null when none. */
        suspend fun findSessionById(
            dir: File,
            id: String,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): File? = withContext(ioDispatcher) {
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
                ?: return@withContext null
            files.firstOrNull { readSessionHeader(it)?.id == id }
        }
    }
}
