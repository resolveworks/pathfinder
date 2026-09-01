package works.resolve.pathfinder.data.sessions

import java.io.File
import java.io.IOException
import kotlin.time.Clock
import works.resolve.pathfinder.ai.utils.uuidv7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.attr

/**
 * File-backed append-only session store over the JSONL v4 mutation-log
 * format (pi's JsonlSessionRepo + JsonlSessionStorage,
 * packages/agent/src/harness/session/jsonl/). One `<id>.jsonl` file per
 * session under [root]: a header line plus one mutation line per append.
 * Writes append to the file instead of rewriting it; all operations are
 * serialized through a [Mutex] (pi's `tail` promise) and performed on
 * [ioDispatcher]. Credentials and request options never appear in session
 * files.
 *
 * Storage-assigned seq (P0-1): every appended mutation consumes the next
 * consecutive seq; [save] syncs a conversation snapshot by appending the
 * entries not yet in the log (emitting a lane mutation whenever the tree
 * branched away from the lane leaf, mirroring pi's moveLane-before-append
 * event order), then moving the lane to the snapshot's leaf and updating
 * the name fact. Re-syncing an already-persisted snapshot is a no-op, so a
 * partially-failed save can simply be retried.
 *
 * Old snapshot formats (whole-file "format 3" and earlier) are rejected
 * outright per the disposable-data policy: only `.jsonl` v4 files are
 * listed, and anything else on disk is ignored. A torn final append (JSON
 * syntax error on the last line) is repaired on load by atomically
 * publishing the valid prefix; an unterminated tail gets its newline
 * appended.
 *
 * Disciplines retained from the snapshot store: bounded reads
 * ([maxFileBytes]), id/filename cross-check (the header id must match the
 * file name), defensive copies, and type-only telemetry errors
 * (`pf.session.*` spans record the session id, outcome, and exception type
 * — never paths or transcript content).
 */
class SessionStore(
    private val root: File,
    private val clock: Clock = Clock.System,
    /** Session ids default to pi's `options.id ?? uuidv7()` (agent jsonl repo). */
    private val idFactory: () -> String = ::uuidv7,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxFileBytes: Long = MAX_FILE_BYTES,
    private val telemetryContext: TelemetryContext = NOOP_TELEMETRY_CONTEXT,
) : SessionRepository {

    /** Upper bound on a single session file to avoid reading unbounded/corrupt files. */
    val maxFileBytes: Long

    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        this.maxFileBytes = maxFileBytes
    }

    private val mutex = Mutex()

    /**
     * Open storages replayed from their files (pi keeps live JsonlSessionStorage
     * instances per open Session; Pathfinder caches them per id so appends
     * continue the in-memory [SessionState] without re-reading the log).
     */
    private val openStorages = HashMap<String, JsonlSessionStorage>()

    /** Creates and persists a new (initially empty) session. */
    override suspend fun create(title: String): Session = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(idFactory())
            writeSpanned(id) {
                ensureRoot()
                val createdAt = clock.now().toEpochMilliseconds()
                val storage = JsonlSessionStorage.create(fileFor(id), JsonlCodec.JsonlV4Header(id = id, createdAt = createdAt))
                // The title rides as the name fact (pi's fact mutations; a
                // session always carries one so summaries stay complete).
                storage.setName(title)
                openStorages[id] = storage
                storage.toSession(fileFor(id).lastModified())
            }
        }
    }

    /** Lists session summaries, newest-updated first; unreadable entries are skipped. */
    override suspend fun summaries(): List<SessionSummary> = mutex.withLock {
        withContext(ioDispatcher) {
            sessionFiles()
                .mapNotNull { file ->
                    summarySpanned(file)?.let { session ->
                        SessionSummary(
                            id = session.id,
                            title = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            messageCount = session.messages.size,
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    /**
     * Divergence from pi's listing (listJsonlSessionMetadata reads headers
     * only): Pathfinder's summary surface needs the title (a name fact, not
     * header data) and the message count, so summaries do a bounded full
     * read + replay instead. Android session directories stay small enough
     * that the extra read does not justify a parallel header-only shape.
     */
    override suspend fun load(id: String): Session? = mutex.withLock {
        withContext(ioDispatcher) {
            val safeId = requireId(id)
            val file = fileFor(safeId)
            if (!file.isFile) {
                null
            } else {
                loadSpanned(file)?.let(::defensiveCopy)
            }
        }
    }

    /**
     * Appends [session]'s unpersisted state to the mutation log (see the
     * class KDoc's sync algorithm) and returns the stored session — the
     * storage-assigned entries, current leaf, and name, with
     * [Session.updatedAt] from the file's modification time.
     */
    override suspend fun save(session: Session): Session = mutex.withLock {
        withContext(ioDispatcher) {
            writeSpanned(session.id) {
                syncSession(
                    id = session.id,
                    entries = session.entries.toList(),
                    leafId = session.leafId,
                    title = session.title,
                )
            }
        }
    }

    /** Deletes a session; true when it existed. */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            val safeId = requireId(id)
            openStorages.remove(safeId)
            val file = fileFor(safeId)
            file.isFile && file.delete()
        }
    }

    /**
     * Appends a lane record to the session's log (pi's Session.appendRecord
     * over JsonlSessionStorage.appendRecord): storage assigns seq and
     * timestamp; the single-open-operation invariant is enforced. Records
     * append immediately — a record may precede the buffered entries it
     * references in seq order (see [LaneRecord]).
     *
     * @throws SessionDataException when the session does not exist or the
     * record violates the log invariants.
     */
    override suspend fun appendRecord(sessionId: String, record: LaneRecord): LaneRecord = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            val storage = storageFor(id, fileFor(id))
                ?: throw SessionDataException("Session not found: unknown")
            try {
                storage.appendRecord(record)
            } catch (e: IOException) {
                throw SessionDataException("Failed to append session", e)
            }
        }
    }

    /** pi's findOpenOperations (see [SessionState.findOpenOperations]). */
    override suspend fun openOperations(
        sessionId: String,
        lane: String,
        limit: Int?,
    ): List<LaneRecord.OperationStartedRecord> = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            storageFor(id, fileFor(id))?.findOpenOperations(lane, limit)
                ?: throw SessionDataException("Session not found: unknown")
        }
    }

    /** pi's getStats: the incremental message/usage fold of the session's log. */
    override suspend fun stats(sessionId: String): SessionStats = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            storageFor(id, fileFor(id))?.stats()
                ?: throw SessionDataException("Session not found: unknown")
        }
    }

    // ---- internals ----

    private fun defensiveCopy(session: Session): Session =
        session.copy(entries = session.entries.toList())

    private fun fileFor(id: String): File = File(root, "$id.jsonl")

    private fun sessionFiles(): List<File> =
        root.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }?.sortedBy { it.name }
            ?: emptyList()

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw IOException("Session directory is unavailable")
        }
    }

    /** The session's open storage, replaying the file on first touch. Null when the file is gone. */
    private fun storageFor(id: String, file: File): JsonlSessionStorage? {
        openStorages[id]?.let { return it }
        if (!file.isFile) return null
        val storage = JsonlSessionStorage.load(file, id, maxFileBytes)
        openStorages[id] = storage
        return storage
    }

    /**
     * Diffs [entries] against the log and appends what is missing: for each
     * new entry in order, a lane mutation first when it parents elsewhere
     * than the lane's current leaf (the persisted equivalent of the branch
     * navigation that produced the sibling), then the entry mutation with
     * its storage-assigned seq. Finally the lane moves to [leafId] and the
     * name fact is updated to [title] when they changed.
     */
    private fun syncSession(id: String, entries: List<SessionEntry>, leafId: String?, title: String): Session {
        val file = fileFor(id)
        val storage = storageFor(id, file) ?: throw SessionDataException("Session not found: unknown")
        for (entry in entries) {
            if (storage.hasEntry(entry.id)) continue
            if (entry.parentId != storage.leafId()) storage.moveLeaf(leafId = entry.parentId)
            storage.appendEntry(entry)
        }
        if (storage.leafId() != leafId) storage.moveLeaf(leafId = leafId)
        if (storage.name() != title) storage.setName(title)
        return storage.toSession(file.lastModified())
    }

    private suspend fun writeSpanned(id: String, operation: () -> Session): Session =
        telemetryContext.startSpan(
            SpanOptions(
                name = SPAN_SAVE,
                attributes = mapOf(ATTR_SESSION to attr(id)),
            ),
        ) { span ->
            try {
                val session = operation()
                span.setAttributes(mapOf(ATTR_OUTCOME to attr(OUTCOME_PERSISTED)))
                session
            } catch (e: CancellationException) {
                // Cancellation is not a failure. The span must be settled ok
                // explicitly: the contract's automatic status would otherwise
                // record the CancellationException as an error.
                span.setStatus(SpanStatus.Ok)
                throw e
            } catch (e: Exception) {
                span.setStatus(typeOnlyError(e))
                throw SessionDataException("Failed to write session", e)
            }
        }

    /**
     * Reads and replays [file] under a load (or summary) telemetry span.
     * [skippedOutcome] marks the summary path, where failures are recorded
     * and the entry skipped instead of rethrown.
     */
    private suspend fun readSpanned(
        file: File,
        spanName: String,
        skippedOutcome: String?,
    ): Session? {
        val id = file.name.removeSuffix(".jsonl")
        return telemetryContext.startSpan(
            SpanOptions(name = spanName, attributes = mapOf(ATTR_SESSION to attr(id))),
        ) { span ->
            try {
                val session = replay(file, id)
                span.setAttributes(mapOf(ATTR_OUTCOME to attr(OUTCOME_LOADED)))
                session
            } catch (e: CancellationException) {
                // Cancellation is not a failure; settle ok (see write above).
                span.setStatus(SpanStatus.Ok)
                throw e
            } catch (e: Exception) {
                span.setStatus(typeOnlyError(e))
                if (skippedOutcome != null) {
                    span.setAttributes(mapOf(ATTR_OUTCOME to attr(skippedOutcome)))
                    null
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun loadSpanned(file: File): Session? = readSpanned(file, SPAN_LOAD, null)
    private suspend fun summarySpanned(file: File): Session? = readSpanned(file, SPAN_SUMMARY, OUTCOME_SKIPPED)

    /** Replays [file], caching the storage so later appends continue its state. */
    private fun replay(file: File, id: String): Session {
        val storage = JsonlSessionStorage.load(file, id, maxFileBytes)
        openStorages[id] = storage
        return storage.toSession(file.lastModified())
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024

        /** App-owned span vocabulary (pi packages define `pi.*` schemas; Pathfinder's are `pf.*`). */
        private const val SPAN_SAVE = "pf.session.save"
        private const val SPAN_LOAD = "pf.session.load"
        private const val SPAN_SUMMARY = "pf.session.summary"
        private const val ATTR_SESSION = "pf.session.id"
        private const val ATTR_OUTCOME = "pf.session.outcome"
        private const val OUTCOME_PERSISTED = "persisted"
        private const val OUTCOME_LOADED = "loaded"
        private const val OUTCOME_SKIPPED = "skipped"

        /** Exception messages can embed filesystem paths; record the type only. */
        private fun typeOnlyError(error: Throwable): SpanStatus = SpanStatus.Error(
            TelemetryError(name = error::class.qualifiedName ?: error::class.simpleName ?: "unknown", message = ""),
        )
    }
}
