package works.resolve.pathfinder.data.sessions

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.utils.uuidv7
import kotlinx.serialization.json.JsonElement

/**
 * Append-only storage for one JSONL v4 session file, porting pi's
 * JsonlSessionStorage (packages/agent/src/harness/session/jsonl/storage.ts):
 * a header line followed by one [SessionMutation] line per write, an
 * in-memory [SessionState] replayed from the file on load, and torn-tail
 * repair.
 *
 * Divergences from upstream (narrowest faithful adaptations, audit P0-2):
 * - Writes are synchronous and callers serialize them (Pathfinder's
 *   [SessionStore] mutex on an IO dispatcher replaces pi's `tail` promise
 *   chain).
 * - appendEntry receives an entry that already carries its id, parentId,
 *   and timestamp (Pathfinder's Conversation mints them as the live tree);
 *   pi's storage assigns all three. The storage-assigned [SessionEntry.seq]
 *   and the same append-time validation (unused id, parent existence,
 *   chaining to the appending lane's leaf) are preserved; the parentId check
 *   replaces pi's assignment because the value is identical.
 * - appendRecord/fork/getLog/queries are not ported beyond
 *   [appendRecord], [findOpenOperations], the query passthroughs, and
 *   [fork]: getLog is P2-5, and Pathfinder reads the replayed state
 *   instead of queries.
 * - timestamp on entry append is the caller's (Conversation-minted) value,
 *   not Date.now() assigned here; record appends assign [clock] time like
 *   pi's appendRecord (records have no producer-minted timestamp).
 */
internal class JsonlSessionStorage private constructor(
    val file: File,
    val header: JsonlCodec.JsonlV4Header,
    private val state: SessionState,
    private val clock: Clock = Clock.System,
) {
    val nextSequence: Long
        get() = state.nextSequence

    /** The main lane's current leaf (Pathfinder's single-lane projection). */
    fun leafId(): String? = state.requireLane(SessionState.LANE_MAIN)

    /** The [lane]'s current leaf; throws when the lane does not exist. */
    fun leafId(lane: String): String? = state.requireLane(lane)

    /** Throws when [lane] does not exist; returns its leaf. */
    fun requireLane(lane: String): String? = state.requireLane(lane)

    /** Entry lookup by id. */
    fun entry(id: String): SessionEntry? = state.entry(id)

    /** Entry label fact by id. */
    fun label(id: String): String? = state.label(id)

    /** pi's getLanes. */
    fun getLanes(): List<LanePointer> = state.getLanes()

    /** pi's createLane: registers [lane] at [at]; the lane must not exist. */
    fun createLane(lane: String, at: String?) {
        state.validateNewLane(lane)
        state.validateTarget(at)
        appendAndApply(SessionMutation.Lane(state.nextSequence, lane, at))
    }

    /** pi's moveLane: moves an existing [lane] to [to]. */
    fun moveLane(lane: String = SessionState.LANE_MAIN, to: String?) {
        state.requireLane(lane)
        state.validateTarget(to)
        appendAndApply(SessionMutation.Lane(state.nextSequence, lane, to))
    }

    /** Whether an entry with [id] is already in the log (sync diff). */
    fun hasEntry(id: String): Boolean = state.entry(id) != null

    fun entries(): List<SessionEntry> = state.entries()

    fun messageCount(): Int = state.messageCount()

    /** Records in append (seq) order (pi's findRecords without queries). */
    fun records(): List<LaneRecord> = state.records()

    /** pi's getStats(): the incremental message/usage fold of the replayed log. */
    fun stats(): SessionStats = state.stats()

    /** pi's findOpenOperations (see [SessionState.findOpenOperations]). */
    fun findOpenOperations(lane: String = SessionState.LANE_MAIN, limit: Int? = null): List<LaneRecord.OperationStartedRecord> =
        state.findOpenOperations(lane, limit)

    /** pi's findEntries (see [SessionState.findEntries]). */
    fun findEntries(query: EntryQuery = EntryQuery()): List<SessionEntry> = state.findEntries(query)

    /** pi's findEntriesOnBranch, storage-level signature ([start] required). */
    fun findEntriesOnBranch(query: BranchEntryQuery): List<SessionEntry> = state.findEntriesOnBranch(query)

    /** pi's findRecords (see [SessionState.findRecords]). */
    fun findRecords(query: RecordQuery = RecordQuery()): List<LaneRecord> = state.findRecords(query)

    /** Latest-wins session name fact; Pathfinder's title carrier. */
    fun name(): String? = state.name

    /** Projects the replayed state into the read-side [Session] value. */
    fun toSession(updatedAt: Long): Session = Session(
        id = header.id,
        title = name() ?: "",
        createdAt = header.createdAt,
        updatedAt = updatedAt,
        entries = entries(),
        leafId = leafId(),
    )

    /**
     * Appends [entry] to [lane], assigning the storage seq (pi's
     * appendEntry(entry, lane)). Throws [SessionDataException] when the id
     * is used or the entry does not chain to the lane's current leaf (pi's
     * appendEntry invariant; callers emit a lane mutation first when the
     * tree branched).
     */
    fun appendEntry(entry: SessionEntry, lane: String = SessionState.LANE_MAIN): SessionEntry {
        val leaf = state.requireLane(lane)
        state.validateUnusedId(entry.id)
        if (entry.parentId != leaf) {
            throw SessionDataException("Invalid session mutation: does not chain to the lane leaf")
        }
        val stored = entry.withSeq(state.nextSequence)
        appendAndApply(SessionMutation.Entry(lane, stored))
        return stored
    }

    /**
     * Appends a record mutation, porting pi's appendRecord (storage.ts):
     * the lane must exist, the id must be unused, and a lane may not open a
     * second operation while one is open (pi throws SessionError
     * "storage", `Lane <lane> already has an open operation <id>`). The
     * storage assigns seq and timestamp and returns the stored record.
     *
     * No flush of buffered entries is required or performed: pi's
     * applyMutation validates no payload references, and records may
     * precede the entries they reference in seq order (see [LaneRecord]).
     */
    fun appendRecord(record: LaneRecord): LaneRecord {
        state.requireLane(record.lane)
        state.validateUnusedId(record.id)
        val currentOpenOperationId = state.findOpenOperations(record.lane, limit = 1).firstOrNull()?.id
        if (record is LaneRecord.OperationStartedRecord && currentOpenOperationId != null) {
            throw SessionDataException("Lane ${record.lane} already has an open operation $currentOpenOperationId")
        }
        val stored = record.withAssigned(state.nextSequence, clock.now().toEpochMilliseconds())
        appendAndApply(SessionMutation.Record(stored))
        return stored
    }

    /** Appends a name fact mutation (pi's setName). */
    fun setName(name: String?) {
        appendAndApply(SessionMutation.Fact.Name(state.nextSequence, name))
    }

    /** Appends a label fact mutation (pi's setLabel). */
    fun setLabel(targetId: String, label: String?) {
        state.validateTarget(targetId)
        appendAndApply(SessionMutation.Fact.Label(state.nextSequence, targetId, label))
    }

    /**
     * pi's Session.appendMessageToLane: mints the entry id and timestamp
     * here (pi's idGenerator + storage assignment) and appends it to
     * [lane], returning the entry id.
     */
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

    /** pi's Session.appendCustomEntryToLane; returns the entry id. */
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
     * pi's JsonlSessionStorage.fork: builds [options]' mutation batch via
     * [SessionState.createForkMutations], publishes the forked file
     * atomically (header line + one mutation line each — the same bytes pi
     * writes through a temp storage), then loads it with full validation.
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
            throw SessionDataException("Failed to append session", e)
        }
        // Apply after the durable write, like pi's appendMutation/applyMutation order.
        state.applyMutation(mutation)
    }

    companion object {
        /** pi's JsonlSessionStorage.create: writes the header line and returns an empty storage. */
        fun create(file: File, header: JsonlCodec.JsonlV4Header, clock: Clock = Clock.System): JsonlSessionStorage {
            try {
                file.writeText(JsonlCodec.encodeHeader(header))
            } catch (e: IOException) {
                throw SessionDataException("Failed to initialize session", e)
            }
            return JsonlSessionStorage(file, header, SessionState(), clock)
        }

        /**
         * pi's JsonlSessionStorage.load: replays the file with full
         * validation, repairs a torn tail (an unacknowledged partial final
         * append — a JSON syntax error on the last line — is dropped by
         * atomically publishing the valid prefix), and repairs an
         * unterminated tail by appending the missing newline.
         *
         * @throws SessionDataException on unreadable, oversized, or invalid
         * files; the header id must match [expectedId] (the store's
         * id/filename cross-check).
         */
        fun load(file: File, expectedId: String, maxFileBytes: Long, clock: Clock = Clock.System): JsonlSessionStorage {
            if (file.length() > maxFileBytes) {
                throw SessionDataException("Session file exceeds size limit")
            }
            val content = try {
                file.readText()
            } catch (e: IOException) {
                throw SessionDataException("Cannot read session file", e)
            }
            if (content.length > maxFileBytes) {
                throw SessionDataException("Session file exceeds size limit")
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
                throw SessionDataException("Session data does not match its file")
            }
            val state = SessionState()
            for (index in 1 until physicalLines.size) {
                val line = physicalLines[index]
                val mutation = try {
                    JsonlCodec.decodeMutation(line)
                } catch (e: JsonlCodec.JsonlDecodeError) {
                    val isTornTail = index == physicalLines.size - 1 && e.kind == JsonlCodec.JsonlDecodeError.Kind.SYNTAX
                    if (isTornTail) {
                        // Drop the unacknowledged partial append by atomically publishing the valid prefix.
                        val validPrefix = physicalLines.subList(0, index).joinToString("\n") + "\n"
                        publishFileAtomically(file) { temp -> temp.writeText(validPrefix) }
                        return JsonlSessionStorage(file, header, state, clock)
                    }
                    throw invalidFile(file, index + 1, e.message ?: "invalid mutation")
                }
                try {
                    state.applyMutation(mutation)
                } catch (e: SessionDataException) {
                    throw invalidFile(file, index + 1, e.message ?: "invalid mutation")
                }
            }
            if (!content.endsWith("\n")) {
                try {
                    file.appendText("\n")
                } catch (e: IOException) {
                    throw SessionDataException("Failed to repair unterminated session tail", e)
                }
            }
            return JsonlSessionStorage(file, header, state, clock)
        }

        /**
         * pi's publishFileAtomically (storage.ts): stage a complete sibling
         * temp file, then atomically rename it over the destination. A crash
         * while populating leaves only the ignored `.tmp` file behind. Falls
         * back to a non-atomic replace where the filesystem has no atomic
         * move (same discipline as the store's snapshot writes had).
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
                throw SessionDataException("Failed to stage torn-tail repair", e)
            }
        }

        /** pi's invalidFile (jsonl/errors.ts): line-addressed invalid-session error. */
        private fun invalidFile(file: File, line: Int, cause: String): SessionDataException =
            SessionDataException("Invalid JSONL v4 session: line $line $cause")
    }
}
