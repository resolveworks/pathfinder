package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.uuidv7
import kotlinx.serialization.json.JsonElement

/**
 * Append-only storage for one JSONL v4 session file: a header line followed
 * by one [SessionMutation] line per write, an in-memory [SessionState]
 * replayed from the file on load, and torn-tail repair.
 *
 * Divergences from pi's storage: writes are synchronous and callers
 * serialize them (the [SessionStore] mutex replaces pi's `tail` promise
 * chain), and [appendEntry] receives an entry that already carries its id,
 * parentId, and timestamp (Pathfinder's Conversation mints them as the live
 * tree; storage still assigns [SessionEntry.seq] and validates chaining to
 * the lane leaf).
 */
internal class JsonlSessionStorage private constructor(
    val file: File,
    val header: JsonlCodec.JsonlV4Header,
    private val state: SessionState,
    private val clock: Clock = Clock.System,
) {
    val nextSequence: Long
        get() = state.nextSequence

    /** The main lane's current leaf. */
    fun leafId(): String? = state.requireLane(SessionState.LANE_MAIN)

    fun leafId(lane: String): String? = state.requireLane(lane)

    /** Throws when [lane] does not exist; returns its leaf. */
    fun requireLane(lane: String): String? = state.requireLane(lane)

    fun entry(id: String): SessionEntry? = state.entry(id)

    fun label(id: String): String? = state.label(id)

    fun getLanes(): List<LanePointer> = state.getLanes()

    /** Registers [lane] at [at]; the lane must not exist. */
    fun createLane(lane: String, at: String?) {
        state.validateNewLane(lane)
        state.validateTarget(at)
        appendAndApply(SessionMutation.Lane(state.nextSequence, lane, at))
    }

    fun moveLane(lane: String = SessionState.LANE_MAIN, to: String?) {
        state.requireLane(lane)
        state.validateTarget(to)
        appendAndApply(SessionMutation.Lane(state.nextSequence, lane, to))
    }

    fun hasEntry(id: String): Boolean = state.entry(id) != null

    fun entries(): List<SessionEntry> = state.entries()

    fun messageCount(): Int = state.messageCount()

    /** Records in append (seq) order. */
    fun records(): List<LaneRecord> = state.records()

    /** The incremental message/usage fold of the replayed log. */
    fun stats(): SessionStats = state.stats()

    fun findOpenOperations(lane: String = SessionState.LANE_MAIN, limit: Int? = null): List<LaneRecord.OperationStartedRecord> =
        state.findOpenOperations(lane, limit)

    fun findEntries(query: EntryQuery = EntryQuery()): List<SessionEntry> = state.findEntries(query)

    fun findEntriesOnBranch(query: BranchEntryQuery): List<SessionEntry> = state.findEntriesOnBranch(query)

    fun findRecords(query: RecordQuery = RecordQuery()): List<LaneRecord> = state.findRecords(query)

    /** Incremental tail reads. */
    fun getLog(afterSeq: Long? = null, limit: Int? = null): List<LogItem> = state.getLog(afterSeq, limit)

    /** Latest-wins session name fact; Pathfinder's title carrier. */
    fun name(): String? = state.name

    fun toSession(updatedAt: Long): Session = Session(
        id = header.id,
        title = name() ?: "",
        createdAt = header.createdAt,
        updatedAt = updatedAt,
        entries = entries(),
        leafId = leafId(),
    )

    /**
     * Appends [entry] to [lane], assigning the storage seq. Throws
     * [SessionError] (invalid_entry) when the id is used or the entry does
     * not chain to the lane's current leaf — callers emit a lane mutation
     * first when the tree branched — and (invalid_payload) when the encoded
     * mutation is not JSON-safe.
     */
    fun appendEntry(entry: SessionEntry, lane: String = SessionState.LANE_MAIN): SessionEntry {
        val leaf = state.requireLane(lane)
        state.validateUnusedId(entry.id)
        if (entry.parentId != leaf) {
            throw SessionError(
                SessionErrorCode.INVALID_ENTRY,
                "Invalid session mutation: does not chain to the lane leaf",
            )
        }
        val stored = entry.withSeq(state.nextSequence)
        assertJsonSerializable(lenientJson.parseToJsonElement(JsonlCodec.encodeMutation(SessionMutation.Entry(lane, stored))))
        appendAndApply(SessionMutation.Entry(lane, stored))
        return stored
    }

    /**
     * Appends a record mutation: the lane must exist, the id must be
     * unused, and a lane may not open a second operation while one is open.
     * Storage assigns seq and timestamp.
     *
     * Records append immediately: a record may precede the entries it
     * references in seq order (see [LaneRecord]).
     */
    fun appendRecord(record: LaneRecord): LaneRecord {
        state.requireLane(record.lane)
        state.validateUnusedId(record.id)
        val currentOpenOperationId = state.findOpenOperations(record.lane, limit = 1).firstOrNull()?.id
        if (record is LaneRecord.OperationStartedRecord && currentOpenOperationId != null) {
            throw SessionError(
                SessionErrorCode.STORAGE,
                "Lane ${record.lane} already has an open operation $currentOpenOperationId",
            )
        }
        val stored = record.withAssigned(state.nextSequence, clock.now().toEpochMilliseconds())
        assertJsonSerializable(lenientJson.parseToJsonElement(JsonlCodec.encodeMutation(SessionMutation.Record(stored))))
        appendAndApply(SessionMutation.Record(stored))
        return stored
    }

    fun setName(name: String?) {
        appendAndApply(SessionMutation.Fact.Name(state.nextSequence, name))
    }

    /** Appends a label fact; [targetId] must be an existing entry. */
    fun setLabel(targetId: String, label: String?) {
        state.validateTarget(targetId)
        appendAndApply(SessionMutation.Fact.Label(state.nextSequence, targetId, label))
    }

    /** Mints the entry id and timestamp, then appends to [lane]. */
    fun appendMessage(message: Message, lane: String = SessionState.LANE_MAIN): String {
        val entry = MessageEntry(
            id = uuidv7(),
            parentId = state.requireLane(lane),
            timestamp = clock.now().toEpochMilliseconds(),
            message = message,
        )
        appendEntry(entry, lane)
        return entry.id
    }

    fun appendCustomEntry(customType: String, data: JsonElement? = null, lane: String = SessionState.LANE_MAIN): String {
        val entry = CustomEntry(
            id = uuidv7(),
            parentId = state.requireLane(lane),
            timestamp = clock.now().toEpochMilliseconds(),
            customType = customType,
            data = data,
        )
        appendEntry(entry, lane)
        return entry.id
    }

    /**
     * Builds [options]' mutation batch via [SessionState.createForkMutations],
     * publishes the forked file atomically (header line + one mutation line
     * each), then loads it with full validation.
     */
    fun fork(
        destination: File,
        header: JsonlCodec.JsonlV4Header,
        options: ForkOptions,
        maxFileBytes: Long,
    ): JsonlSessionStorage {
        val mutations = state.createForkMutations(options)
        publishFileAtomically(destination) { temp ->
            temp.writeText(JsonlCodec.encodeHeader(header) + mutations.joinToString("") { JsonlCodec.encodeMutation(it) })
        }
        return load(destination, header.id, maxFileBytes, clock)
    }

    private fun appendAndApply(mutation: SessionMutation) {
        try {
            file.appendText(JsonlCodec.encodeMutation(mutation))
        } catch (e: IOException) {
            throw SessionError(SessionErrorCode.STORAGE, "Failed to append session", e)
        }
        // Apply in memory only after the durable append succeeds.
        state.applyMutation(mutation)
    }

    companion object {
        fun create(file: File, header: JsonlCodec.JsonlV4Header, clock: Clock = Clock.System): JsonlSessionStorage {
            try {
                file.writeText(JsonlCodec.encodeHeader(header))
            } catch (e: IOException) {
                throw SessionError(SessionErrorCode.STORAGE, "Failed to initialize session", e)
            }
            return JsonlSessionStorage(file, header, SessionState(), clock)
        }

        /**
         * Replays the file with full validation. A torn final append (JSON
         * syntax error on the last line) is repaired by atomically publishing
         * the valid prefix; an unterminated tail gets its newline appended.
         *
         * @throws SessionError on unreadable (storage), oversized (storage),
         * or invalid (invalid_entry) files; the header id must match
         * [expectedId] (the store's id/filename cross-check).
         */
        fun load(file: File, expectedId: String, maxFileBytes: Long, clock: Clock = Clock.System): JsonlSessionStorage {
            if (file.length() > maxFileBytes) {
                throw SessionError(SessionErrorCode.STORAGE, "Session file exceeds size limit")
            }
            val content = try {
                file.readText()
            } catch (e: IOException) {
                throw SessionError(SessionErrorCode.STORAGE, "Cannot read session file", e)
            }
            if (content.length > maxFileBytes) {
                throw SessionError(SessionErrorCode.STORAGE, "Session file exceeds size limit")
            }
            val physicalLines = content.split("\n").toMutableList()
            if (physicalLines.isNotEmpty() && physicalLines.last() == "") physicalLines.removeAt(physicalLines.size - 1)
            if (physicalLines.isEmpty() || physicalLines[0].isEmpty()) {
                throw invalidFile(file, 1, "is missing a header")
            }
            val header = try {
                JsonlCodec.decodeHeader(physicalLines[0])
            } catch (e: JsonlCodec.JsonlDecodeError) {
                throw invalidFile(file, 1, e.message ?: "invalid header")
            }
            if (header.id != expectedId) {
                throw SessionError(SessionErrorCode.INVALID_ENTRY, "Session id does not match header: $expectedId")
            }
            val state = SessionState()
            for (index in 1 until physicalLines.size) {
                val line = physicalLines[index]
                val mutation = try {
                    JsonlCodec.decodeMutation(line)
                } catch (e: JsonlCodec.JsonlDecodeError) {
                    val isTornTail = index == physicalLines.size - 1 && e.kind == JsonlCodec.JsonlDecodeError.Kind.SYNTAX
                    if (isTornTail) {
                        val validPrefix = physicalLines.subList(0, index).joinToString("\n") + "\n"
                        publishFileAtomically(file) { temp -> temp.writeText(validPrefix) }
                        return JsonlSessionStorage(file, header, state, clock)
                    }
                    throw invalidFile(file, index + 1, e.message ?: "invalid mutation")
                }
                try {
                    state.applyMutation(mutation)
                } catch (e: SessionError) {
                    throw invalidFile(file, index + 1, e.message ?: "invalid mutation")
                }
            }
            if (!content.endsWith("\n")) {
                try {
                    file.appendText("\n")
                } catch (e: IOException) {
                    throw SessionError(SessionErrorCode.STORAGE, "Failed to repair unterminated session tail", e)
                }
            }
            return JsonlSessionStorage(file, header, state, clock)
        }

        /**
         * Stages a complete sibling temp file, then atomically renames it over
         * the destination, so readers never see a partially written file and a
         * crash while populating leaves only the ignored `.tmp` file behind.
         * Callers must serialize publications to the same destination (the
         * `.tmp` path is deterministic). Falls back to a non-atomic replace
         * where the filesystem has no atomic move.
         */
        private inline fun publishFileAtomically(destination: File, populate: (File) -> Unit) {
            val temp = File(destination.parentFile, destination.name + ".tmp")
            try {
                populate(temp)
                try {
                    Files.move(
                        temp.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                temp.delete()
                throw SessionError(SessionErrorCode.STORAGE, "Failed to stage torn-tail repair", e)
            }
        }

        private fun invalidFile(file: File, line: Int, cause: String): SessionError =
            SessionError(SessionErrorCode.INVALID_ENTRY, "Invalid JSONL v4 session: line $line $cause")
    }
}
