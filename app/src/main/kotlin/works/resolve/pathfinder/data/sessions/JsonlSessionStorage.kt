package works.resolve.pathfinder.data.sessions

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 * - appendRecord/fork/getLog/queries are not ported: records have no
 *   producers yet (P0-3), fork is P1-2, and Pathfinder reads the replayed
 *   state instead of queries.
 * - timestamp on append is the caller's (Conversation-minted) value, not
 *   Date.now() assigned here.
 */
internal class JsonlSessionStorage private constructor(
    val file: File,
    val header: JsonlCodec.JsonlV4Header,
    private val state: SessionState,
) {
    val nextSequence: Long
        get() = state.nextSequence

    /** The main lane's current leaf (Pathfinder's single-lane projection). */
    fun leafId(): String? = state.requireLane(SessionState.LANE_MAIN)

    /** Whether an entry with [id] is already in the log (sync diff). */
    fun hasEntry(id: String): Boolean = state.entry(id) != null

    fun entries(): List<SessionEntry> = state.entries()

    fun messageCount(): Int = state.messageCount()

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
     * Appends [entry] to the "main" lane, assigning the storage seq. Throws
     * [SessionDataException] when the id is used or the entry does not chain
     * to the lane's current leaf (pi's appendEntry invariant; callers emit a
     * lane mutation first when the tree branched).
     */
    fun appendEntry(entry: SessionEntry): SessionEntry {
        val lane = SessionState.LANE_MAIN
        val leaf = state.requireLane(lane)
        state.validateUnusedId(entry.id)
        if (entry.parentId != leaf) {
            throw SessionDataException("Invalid session mutation: does not chain to the lane leaf")
        }
        val stored = entry.withSeq(state.nextSequence)
        appendAndApply(SessionMutation.Entry(lane, stored))
        return stored
    }

    /** Appends a lane-pointer mutation (pi's moveLane/createLane write path). */
    fun moveLeaf(lane: String = SessionState.LANE_MAIN, leafId: String?) {
        state.requireLane(lane)
        state.validateTarget(leafId)
        appendAndApply(SessionMutation.Lane(state.nextSequence, lane, leafId))
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
        fun create(file: File, header: JsonlCodec.JsonlV4Header): JsonlSessionStorage {
            try {
                file.writeText(JsonlCodec.encodeHeader(header))
            } catch (e: IOException) {
                throw SessionDataException("Failed to initialize session", e)
            }
            return JsonlSessionStorage(file, header, SessionState())
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
        fun load(file: File, expectedId: String, maxFileBytes: Long): JsonlSessionStorage {
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
                        return JsonlSessionStorage(file, header, state)
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
            return JsonlSessionStorage(file, header, state)
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
