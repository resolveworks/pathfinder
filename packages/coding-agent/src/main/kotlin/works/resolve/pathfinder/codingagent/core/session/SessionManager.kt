package works.resolve.pathfinder.codingagent.core.session

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import works.resolve.pathfinder.agent.CompactionDetails
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.utils.uuidv7

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
 * [Mutex] and file IO runs on [ioDispatcher]. Like pi's `_appendEntry`,
 * an append commits in-memory state first and persists second, so a
 * storage failure throws [SessionError] with code [SessionErrorCode.STORAGE]
 * while the entry stays committed in memory.
 */
class SessionManager private constructor(
    private val dir: File,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    private val entryIdFactory: () -> String,
    private val header: JsonlCodec.SessionHeader,
    val sessionFile: File?,
    initialFlushed: Boolean
) {
    private val mutex = Mutex()

    val sessionId: String = header.id

    @Volatile
    var conversation: Conversation
        private set

    val entries: List<SessionEntry> get() = conversation.entries

    val leafId: String? get() = conversation.leafId

    private val byId = LinkedHashMap<String, SessionEntry>()
    private var leafIdLocked: String? = null
    private var flushed = initialFlushed

    init {
        conversation = Conversation(emptyList(), null)
    }

    private fun rebuildSnapshot() {
        conversation = Conversation(byId.values.toList(), leafIdLocked)
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
        val hasAssistant = byId.values.any {
            it is MessageEntry && it.message is AssistantMessage
        }
        if (!hasAssistant) {
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
                usage
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
        details: JsonElement?,
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

    companion object {
        /** Bounded first-line scan for openById, like pi's MAX_SESSION_HEADER_SCAN_BYTES. */
        private const val MAX_HEADER_SCAN_BYTES = 1024 * 1024

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
            for (line in text.lineSequence()) {
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
            }
            manager.rebuildSnapshot()
            if (initializingEmpty) manager.writeFullFileLocked(exclusive = false)
            manager
        }

        /**
         * Read the first valid line of [file] bounded by
         * [MAX_HEADER_SCAN_BYTES]; a parsed non-header first entry means
         * the file is not a session. Oversized prefixes yield null.
         */
        private fun readHeaderBounded(file: File): JsonlCodec.SessionHeader? {
            file.inputStream().use { input ->
                val buffer = ByteArray(MAX_HEADER_SCAN_BYTES + 1)
                var filled = 0
                var newline = -1
                while (filled <= MAX_HEADER_SCAN_BYTES) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read == -1) break
                    filled += read
                    newline = buffer.indexOf('\n'.code.toByte())
                    if (newline != -1) break
                }
                if (newline > MAX_HEADER_SCAN_BYTES) return null
                val firstLine = if (newline == -1) {
                    if (filled > MAX_HEADER_SCAN_BYTES) return null
                    String(buffer, 0, filled, StandardCharsets.UTF_8)
                } else {
                    String(buffer, 0, newline, StandardCharsets.UTF_8)
                }
                return when (val parsed = JsonlCodec.parseLine(firstLine)) {
                    is JsonlCodec.Line.Header -> parsed.header
                    else -> null
                }
            }
        }

        /** Scan `*.jsonl` headers for [id] and open the matching session, or null. */
        suspend fun openById(
            dir: File,
            id: String,
            clock: Clock = Clock.System,
            idFactory: () -> String = ::uuidv7,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            entryIdFactory: () -> String = ::defaultEntryId
        ): SessionManager? = withContext(ioDispatcher) {
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
                ?: return@withContext null
            for (file in files) {
                val header = try {
                    readHeaderBounded(file)
                } catch (_: Exception) {
                    null
                }
                if (header?.id == id) {
                    return@withContext open(file, clock, idFactory, ioDispatcher, entryIdFactory)
                }
            }
            null
        }

        /**
         * List sessions sorted by `modified` descending. Unparseable files
         * are skipped; one corrupt file must not hide the others.
         */
        suspend fun list(
            dir: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): List<SessionInfo> = withContext(ioDispatcher) {
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
                ?: return@withContext emptyList()
            files.mapNotNull(::buildSessionInfo).sortedByDescending { it.modified }
        }

        /** pi's buildSessionInfo: one pass over the file. */
        private fun buildSessionInfo(file: File): SessionInfo? {
            val loaded = loadFile(file).first ?: return null
            var messageCount = 0
            var firstMessage: String? = null
            val allMessages = ArrayList<String>()
            var lastActivity: Long? = null
            for (entry in loaded.entries) {
                if (entry !is MessageEntry) continue
                messageCount++
                val message = entry.message
                if (message !is works.resolve.pathfinder.ai.UserMessage &&
                    message !is AssistantMessage
                ) {
                    continue
                }
                lastActivity = maxOf(lastActivity ?: Long.MIN_VALUE, message.timestamp)
                val content = when (message) {
                    is works.resolve.pathfinder.ai.UserMessage -> message.content
                    is AssistantMessage -> message.content
                    else -> emptyList()
                }
                val text = content.filterIsInstance<TextContent>()
                    .joinToString(" ") { it.text }
                if (text.isEmpty()) continue
                allMessages.add(text)
                if (firstMessage == null && message is works.resolve.pathfinder.ai.UserMessage) {
                    firstMessage = text
                }
            }
            return SessionInfo(
                id = loaded.header.id,
                createdAt = loaded.header.timestamp,
                modified = lastActivity ?: loaded.header.timestamp,
                messageCount = messageCount,
                firstMessage = firstMessage ?: "(no messages)",
                allMessagesText = allMessages.joinToString(" ")
            )
        }
    }
}
