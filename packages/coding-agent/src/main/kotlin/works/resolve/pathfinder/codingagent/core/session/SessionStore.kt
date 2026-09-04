package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.ai.utils.uuidv7
import works.resolve.pathfinder.logging.PathfinderDiagnostics

/**
 * File-backed append-only session store over the JSONL v4 mutation-log
 * format: one `<id>.jsonl` file per session under [root] — a header line
 * plus one mutation line per append. All operations serialize through a
 * [Mutex] on [ioDispatcher]. Credentials and request options never appear
 * in session files.
 *
 * Storage-assigned seq: every appended mutation consumes the next
 * consecutive seq. [save] is idempotent — re-syncing an already-persisted
 * snapshot is a no-op, so a partially-failed save can simply be retried.
 *
 * Only `.jsonl` v4 files are listed and loaded; anything else on disk is
 * ignored, never migrated. A torn final append (JSON syntax error on the
 * last line) is repaired on load by atomically publishing the valid
 * prefix; an unterminated tail gets its newline appended.
 *
 * Telemetry errors are type-only through [PathfinderDiagnostics]:
 * `pf.session.*` spans record the session id, outcome, and exception type
 * — never paths or transcript content; the vocabulary and policy live in
 * the facade.
 */
class SessionStore(
    private val root: File,
    private val clock: Clock = Clock.System,
    private val idFactory: () -> String = ::uuidv7,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxFileBytes: Long = MAX_FILE_BYTES,
    private val diagnostics: PathfinderDiagnostics = PathfinderDiagnostics.NOOP
) : SessionRepository {

    /** Upper bound on a single session file to avoid reading unbounded/corrupt files. */
    val maxFileBytes: Long

    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        this.maxFileBytes = maxFileBytes
    }

    private val mutex = Mutex()

    /** Open storages cached per id so appends continue the in-memory state without replaying the log. */
    private val openStorages = HashMap<String, JsonlSessionStorage>()

    override suspend fun create(title: String): Session = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(idFactory())
            writeSpanned(id) {
                ensureRoot()
                val createdAt = clock.now().toEpochMilliseconds()
                val storage = JsonlSessionStorage.create(
                    fileFor(id),
                    JsonlCodec.JsonlV4Header(id = id, createdAt = createdAt)
                )
                // The title rides as the name fact; a session always carries
                // one so summaries stay complete.
                storage.setName(title)
                openStorages[id] = storage
                storage.toSession(fileFor(id).lastModified())
            }
        }
    }

    /**
     * Unlike pi's header-only listing, summaries replay the whole log: the
     * summary surface needs the title (a name fact, not header data) and
     * the message count. Android session directories stay small enough that
     * the extra read does not justify a parallel header-only shape.
     */
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
                            messageCount = session.messages.size
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }
        }
    }

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
     * Appends [session]'s unpersisted state to the log and returns the
     * stored session — storage-assigned entries, current leaf, and name,
     * with [Session.updatedAt] from the file's mtime.
     */
    override suspend fun save(session: Session): Session = mutex.withLock {
        withContext(ioDispatcher) {
            writeSpanned(session.id) {
                syncSession(
                    id = session.id,
                    entries = session.entries.toList(),
                    leafId = session.leafId,
                    title = session.title
                )
            }
        }
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            val safeId = requireId(id)
            openStorages.remove(safeId)
            val file = fileFor(safeId)
            file.isFile && file.delete()
        }
    }

    /**
     * Appends a lane record: storage assigns seq and timestamp and enforces
     * the single-open-operation invariant. Records append immediately — a
     * record may precede the buffered entries it references in seq order
     * (see [LaneRecord]).
     *
     * @throws SessionError when the session does not exist (not_found) or
     * the record violates the log invariants.
     */
    override suspend fun appendRecord(sessionId: String, record: LaneRecord): LaneRecord =
        mutex.withLock {
            withContext(ioDispatcher) {
                val id = requireId(sessionId)
                val storage = storageFor(id, fileFor(id))
                    ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
                try {
                    storage.appendRecord(record)
                } catch (e: IOException) {
                    throw SessionError(SessionErrorCode.STORAGE, "Failed to append session", e)
                }
            }
        }

    override suspend fun openOperations(
        sessionId: String,
        lane: String,
        limit: Int?
    ): List<LaneRecord.OperationStartedRecord> = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            storageFor(id, fileFor(id))?.findOpenOperations(lane, limit)
                ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
        }
    }

    /** Session stats, folded incrementally from the log. */
    override suspend fun stats(sessionId: String): SessionStats = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            storageFor(id, fileFor(id))?.stats()
                ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
        }
    }

    /**
     * Creates a new session whose log is [options]' mutation batch over
     * this session's replayed state (see [SessionState.createForkMutations]).
     * The new header's parent session id defaults to the source session's id
     * (lineage), and the new file is published atomically. The id defaults
     * to [idFactory]; supplying an existing id throws.
     */
    override suspend fun fork(sourceId: String, options: ForkOptions): Session =
        fork(sourceId, options, id = null, parentSessionId = null)

    suspend fun fork(
        sourceId: String,
        options: ForkOptions,
        id: String? = null,
        parentSessionId: String? = null
    ): Session = mutex.withLock {
        withContext(ioDispatcher) {
            val source = requireId(sourceId)
            writeSpanned(source, PathfinderDiagnostics.SessionWrite.FORK) {
                ensureRoot()
                val sourceStorage = storageFor(source, fileFor(source))
                    ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
                val newId = requireId(id ?: idFactory())
                val destination = fileFor(newId)
                if (destination.isFile) {
                    throw SessionError(
                        SessionErrorCode.ALREADY_EXISTS,
                        "Session already exists: $newId"
                    )
                }
                val forked = sourceStorage.fork(
                    destination = destination,
                    header = JsonlCodec.JsonlV4Header(
                        id = newId,
                        createdAt = clock.now().toEpochMilliseconds(),
                        parentSessionId = parentSessionId ?: source
                    ),
                    options = options,
                    maxFileBytes = maxFileBytes
                )
                openStorages[newId] = forked
                forked.toSession(destination.lastModified())
            }
        }
    }

    /** A lane-scoped projection ([LaneView]) whose writes serialize through this store's mutex + dispatcher. */
    suspend fun view(sessionId: String, lane: String = SessionState.LANE_MAIN): LaneView =
        mutex.withLock {
            withContext(ioDispatcher) {
                val id = requireId(sessionId)
                val storage = storageFor(id, fileFor(id))
                    ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
                storage.requireLane(lane)
                LaneView(
                    lane,
                    storage,
                    object : LaneView.Writer {
                        override suspend fun <T> write(block: (JsonlSessionStorage) -> T): T =
                            withOpenStorage(sessionId, block)
                    }
                )
            }
        }

    suspend fun createLane(sessionId: String, lane: String, at: String?) =
        withOpenStorage(sessionId) {
            it.createLane(lane, at)
        }

    suspend fun moveLane(sessionId: String, lane: String, to: String?) =
        withOpenStorage(sessionId) {
            it.moveLane(lane, to)
        }

    override suspend fun findRecords(sessionId: String, query: RecordQuery): List<LaneRecord> =
        withOpenStorage(sessionId) {
            if (query.operationKind != null && query.type != RecordType.OPERATION_STARTED) {
                throw SessionError(
                    SessionErrorCode.INVALID_QUERY,
                    "operationKind requires type \"operation_started\""
                )
            }
            it.findRecords(query)
        }

    /** Log items after [afterSeq] (exclusive), oldest first, up to [limit]. */
    suspend fun getLog(
        sessionId: String,
        afterSeq: Long? = null,
        limit: Int? = null
    ): List<LogItem> = withOpenStorage(sessionId) { it.getLog(afterSeq, limit) }

    suspend fun parentSessionId(sessionId: String): String? = withOpenStorage(sessionId) {
        it.header.parentSessionId
    }

    private suspend fun <T> withOpenStorage(
        sessionId: String,
        block: (JsonlSessionStorage) -> T
    ): T = mutex.withLock {
        withContext(ioDispatcher) {
            val id = requireId(sessionId)
            block(
                storageFor(id, fileFor(id))
                    ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
            )
        }
    }

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
    private fun syncSession(
        id: String,
        entries: List<SessionEntry>,
        leafId: String?,
        title: String
    ): Session {
        val file = fileFor(id)
        val storage =
            storageFor(id, file)
                ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Session not found: $id")
        for (entry in entries) {
            if (storage.hasEntry(entry.id)) continue
            if (entry.parentId != storage.leafId()) storage.moveLane(to = entry.parentId)
            storage.appendEntry(entry)
        }
        if (storage.leafId() != leafId) storage.moveLane(to = leafId)
        if (storage.name() != title) storage.setName(title)
        return storage.toSession(file.lastModified())
    }

    private suspend fun writeSpanned(
        id: String,
        kind: PathfinderDiagnostics.SessionWrite = PathfinderDiagnostics.SessionWrite.SAVE,
        operation: () -> Session
    ): Session = try {
        // The span records the original failure type; the rewrap below is
        // business behavior and stays outside the recorded boundary.
        diagnostics.sessionWrite(kind, id) { operation() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SessionError(SessionErrorCode.STORAGE, "Failed to write session", e)
    }

    /** Replays [file], caching the storage so later appends continue its state. */
    private fun replay(file: File, id: String): Session {
        val storage = JsonlSessionStorage.load(file, id, maxFileBytes)
        openStorages[id] = storage
        return storage.toSession(file.lastModified())
    }

    private fun sessionIdOf(file: File): String = file.name.removeSuffix(".jsonl")

    private suspend fun loadSpanned(file: File): Session? =
        diagnostics.sessionLoad(sessionIdOf(file)) { replay(file, sessionIdOf(file)) }

    /** Summary read under a `pf.session.summary` span; failures are recorded and the entry skipped. */
    private suspend fun summarySpanned(file: File): Session? =
        diagnostics.sessionSummary(sessionIdOf(file)) { replay(file, sessionIdOf(file)) }

    companion object {
        const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024
    }
}
