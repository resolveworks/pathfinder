package works.resolve.pathfinder.data.sessions

import java.io.File
import java.io.IOException
import kotlin.time.Clock
import works.resolve.pathfinder.ai.utils.uuidv7
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Simple file-backed store for persistent chat sessions. One bounded JSON file
 * per session under [root]; writes are atomic (temp file + replace). All
 * operations are serialized through a [Mutex] and performed on [ioDispatcher].
 * Credentials and request options never appear in session files.
 *
 * Ordinary file-system failures (unwritable directory, failed temp write,
 * unsupported/failed atomic move) surface as generic [SessionDataException]s
 * without leaking paths or transcript content; coroutine cancellation
 * ([CancellationException]) is never swallowed. On filesystems where an atomic
 * move is unsupported the write falls back to a non-atomic replace: the
 * previous target either stays fully intact or is fully replaced, so no
 * partial target is ever left behind — the temp file is always cleaned up.
 *
 * Failures are additionally recorded as sanitized telemetry spans
 * (`pf.session.*`) at the lowest layer, before the original exception is
 * rethrown (or, for [summaries], before the entry is skipped): only the
 * session id, operation outcome, and exception *type* are recorded — never
 * exception messages (which can embed filesystem paths) or transcript
 * content. UI-layer catches over the same operations do not duplicate them.
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

    /** Creates and persists a new (initially empty) session. */
    override suspend fun create(title: String): Session = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val session = Session(
            id = idFactory(),
            title = title,
            createdAt = now,
            updatedAt = now,
            entries = emptyList(),
            leafId = null,
        )
        withContext(ioDispatcher) { write(session) }
        session
    }

    /** Lists session summaries, newest-updated first; unreadable entries are skipped. */
    override suspend fun summaries(): List<SessionSummary> = mutex.withLock {
        withContext(ioDispatcher) {
            sessionFiles()
                .mapNotNull { file ->
                    readSpanned(file, spanName = SPAN_SUMMARY, skippedOutcome = OUTCOME_SKIPPED)
                        ?.let { session ->
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

    /** Loads a session by id, or null when it does not exist. */
    override suspend fun load(id: String): Session? = mutex.withLock {
        withContext(ioDispatcher) {
            val file = fileFor(id)
            if (!file.isFile) null else readSpanned(file)?.let(::defensiveCopy)
        }
    }

    /**
     * Persists [session] atomically, bumping [Session.updatedAt]. Returns the
     * stored session (with the new timestamp and defensive copies).
     */
    override suspend fun save(session: Session): Session = mutex.withLock {
        val stored = defensiveCopy(session).copy(updatedAt = clock.now().toEpochMilliseconds())
        withContext(ioDispatcher) { write(stored) }
        stored
    }

    /** Deletes a session; true when it existed. */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            val file = fileFor(id)
            file.isFile && file.delete()
        }
    }

    // ---- internals ----

    private fun defensiveCopy(session: Session): Session =
        session.copy(entries = session.entries.toList())

    private fun fileFor(id: String): File = File(root, requireId(id) + ".json")

    private fun sessionFiles(): List<File> =
        root.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.sortedBy { it.name }
            ?: emptyList()

    private suspend fun write(session: Session) =
        telemetryContext.startSpan(
            SpanOptions(
                name = SPAN_SAVE,
                attributes = mapOf(ATTR_SESSION to attr(session.id)),
            ),
        ) { span ->
            try {
                writeSpanned(session)
                span.setAttributes(mapOf(ATTR_OUTCOME to attr(OUTCOME_PERSISTED)))
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

    private fun writeSpanned(session: Session) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw IOException("Session directory is unavailable")
        }
        val target = fileFor(session.id)
        // createTempFile requires a >=3-char prefix; pad short ids.
        val temp = File.createTempFile(session.id.padStart(3, '_'), ".tmp", root)
        try {
            temp.writeText(SessionCodec.encode(session))
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Documented fallback: a plain replace still swaps whole
                // files only; no partial target is ever produced.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * Reads and decodes [file] under a load (or summary) telemetry span.
     * [skippedOutcome] marks the summary path, where failures are recorded
     * and the entry skipped instead of rethrown.
     */
    private suspend fun readSpanned(
        file: File,
        spanName: String = SPAN_LOAD,
        skippedOutcome: String? = null,
    ): Session? {
        val id = file.name.removeSuffix(".json")
        return telemetryContext.startSpan(
            SpanOptions(name = spanName, attributes = mapOf(ATTR_SESSION to attr(id))),
        ) { span ->
            try {
                val session = read(file)
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

    private fun read(file: File): Session {
        if (file.length() > maxFileBytes) {
            throw SessionDataException("Session file exceeds size limit")
        }
        val text = try {
            file.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SessionDataException("Cannot read session file", e)
        }
        if (text.length > maxFileBytes) {
            throw SessionDataException("Session file exceeds size limit")
        }
        val session = SessionCodec.decode(text)
        val expectedId = file.name.removeSuffix(".json")
        if (session.id != expectedId) {
            throw SessionDataException("Session data does not match its file")
        }
        return session
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
