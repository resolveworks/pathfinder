package works.resolve.pathfinder.data.sessions

/**
 * Replay state for a session's mutation log, porting pi's SessionState
 * (packages/agent/src/harness/session/state.ts): folds mutations in order,
 * enforcing the log invariants — consecutive 1-based seq, unused ids,
 * existing parents/lanes/label targets, and lane chaining for lane-addressed
 * entry mutations. Record mutations additionally fold pi's
 * openOperationsByLane map and SessionStats accumulation.
 *
 * Divergences (scope of this port, per the session-parity audit P0-1/P0-2):
 * - pi throws SessionError("invalid_entry", "Invalid session mutation: …");
 *   Pathfinder's module exception is [SessionDataException] with the same
 *   message text. The same holds for the query validation (invalid_query)
 *   and fork (invalid_fork_target) messages below — typed error codes are
 *   audit P2-4.
 * - pi's getLog is not ported (P2-5, incremental observer reads).
 * - createForkMutations copies entries by rebinding seq only (Kotlin's
 *   entry payloads are immutable values); pi structuredClones them.
 * - Records are validated exactly as far as upstream validates them: the
 *   lane must exist and the id must be unused; payload references (e.g.
 *   sourceLeafId) are never validated, and records may precede the entries
 *   they reference in seq order (see [LaneRecord]'s KDoc).
 */
internal class SessionState {
    private var sequence = 0L
    private val usedIds = HashSet<String>()
    private val entryList = ArrayList<SessionEntry>()
    private val entriesById = HashMap<String, SessionEntry>()
    private val recordList = ArrayList<LaneRecord>()
    private val openOperationsByLane = HashMap<String, LinkedHashMap<String, LaneRecord.OperationStartedRecord>>()
    private val lanes = LinkedHashMap<String, String?>().apply { put(LANE_MAIN, null) }
    private val labels = HashMap<String, String>()

    private var statsMessageCount = 0
    private var statsCachedTokens = 0L
    private var statsUncachedTokens = 0L
    private var statsTotalTokens = 0L
    private var statsCostTotal = 0.0

    /** Latest-wins session name fact (pi's `name` state). */
    var name: String? = null
        private set

    /** Entries in append (seq) order; defensive copy. */
    fun entries(): List<SessionEntry> = entryList.toList()

    /** Records in append (seq) order; defensive copy. */
    fun records(): List<LaneRecord> = recordList.toList()

    /** Lane pointers in insertion order (pi's getLanes). */
    fun getLanes(): List<LanePointer> = lanes.map { (lane, leafId) -> LanePointer(lane, leafId) }

    fun entry(id: String): SessionEntry? = entriesById[id]

    fun label(id: String): String? = labels[id]

    val nextSequence: Long
        get() = sequence + 1

    /**
     * pi's getStats(): the incremental fold of message entries and usage
     * records (state.ts's applyMutation accumulation). messageCount counts
     * message entries; the token/cost fields sum usage records.
     */
    fun stats(): SessionStats = SessionStats(
        messageCount = statsMessageCount,
        cachedTokens = statsCachedTokens,
        uncachedTokens = statsUncachedTokens,
        totalTokens = statsTotalTokens,
        costTotal = statsCostTotal,
    )

    /** Number of message entries (pi's SessionStats.messageCount fold). */
    fun messageCount(): Int = statsMessageCount

    /** The lane's current leaf; throws when the lane does not exist (pi's requireLane). */
    fun requireLane(lane: String): String? =
        if (lanes.containsKey(lane)) {
            lanes[lane]
        } else {
            throw SessionDataException("Invalid session mutation: Lane not found: $lane")
        }

    /** Throws when [id] is already used by an entry or record (pi's validateUnusedId). */
    fun validateUnusedId(id: String) {
        if (id in usedIds) throw SessionDataException("Session id already exists: $id")
    }

    /** Throws when [lane] already exists (pi's validateNewLane). */
    fun validateNewLane(lane: String) {
        if (lanes.containsKey(lane)) throw SessionDataException("Lane already exists: $lane")
    }

    /** Throws when [targetId] is not an existing entry (pi's validateTarget). */
    fun validateTarget(targetId: String?) {
        if (targetId != null && !entriesById.containsKey(targetId)) {
            throw SessionDataException("Entry not found: $targetId")
        }
    }

    /**
     * Folds [mutation] into the state, enforcing pi's applyMutation
     * validation order: seq must be exactly the next consecutive number,
     * then per-kind checks (duplicate ids, lane existence, lane chaining,
     * parent/label-target existence). Record mutations track
     * openOperationsByLane (operation_started opens, operation_finished
     * closes by runId — abort_requested does not close) and accumulate
     * usage records into the stats fold.
     */
    fun applyMutation(mutation: SessionMutation) {
        val seq = when (mutation) {
            is SessionMutation.Entry -> mutation.entry.seq
            is SessionMutation.Record -> mutation.record.seq
            is SessionMutation.Lane -> mutation.seq
            is SessionMutation.Fact -> mutation.seq
        }
        if (seq != sequence + 1) invalid("has non-consecutive seq $seq")

        when (mutation) {
            is SessionMutation.Entry -> {
                if (mutation.entry.id in usedIds) invalid("contains duplicate id ${mutation.entry.id}")
                if (mutation.lane != null) {
                    if (!lanes.containsKey(mutation.lane)) invalid("references missing lane ${mutation.lane}")
                    if (mutation.entry.parentId != lanes[mutation.lane]) invalid("does not chain to the lane leaf")
                }
                val parent = mutation.entry.parentId
                if (parent != null && !entriesById.containsKey(parent)) invalid("references missing parent $parent")
                sequence = seq
                usedIds.add(mutation.entry.id)
                entryList.add(mutation.entry)
                entriesById[mutation.entry.id] = mutation.entry
                if (mutation.lane != null) lanes[mutation.lane] = mutation.entry.id
                if (mutation.entry is MessageEntry) statsMessageCount += 1
            }
            is SessionMutation.Record -> {
                val record = mutation.record
                if (!lanes.containsKey(record.lane)) invalid("references missing lane ${record.lane}")
                if (record.id in usedIds) invalid("contains duplicate id ${record.id}")
                sequence = seq
                usedIds.add(record.id)
                recordList.add(record)
                when (record) {
                    is LaneRecord.OperationStartedRecord ->
                        openOperationsByLane.getOrPut(record.lane) { LinkedHashMap() }[record.id] = record
                    is LaneRecord.OperationFinishedRecord ->
                        openOperationsByLane[record.lane]?.remove(record.runId)
                    is LaneRecord.UsageRecord -> {
                        // pi's applyMutation usage fold: cacheRead is cached;
                        // input + cacheWrite is uncached; costTotal sums cost.total.
                        statsCachedTokens += record.usage.cacheRead
                        statsUncachedTokens += record.usage.input + record.usage.cacheWrite
                        statsTotalTokens += record.usage.totalTokens
                        statsCostTotal += record.usage.cost.total
                    }
                    is LaneRecord.AbortRequestedRecord, is LaneRecord.DeferredRecord -> Unit
                }
            }
            is SessionMutation.Lane -> {
                if (mutation.leafId != null && !entriesById.containsKey(mutation.leafId)) {
                    invalid("references missing lane target ${mutation.leafId}")
                }
                sequence = seq
                lanes[mutation.lane] = mutation.leafId
            }
            is SessionMutation.Fact -> when (mutation) {
                is SessionMutation.Fact.Name -> {
                    sequence = seq
                    name = mutation.name
                }
                is SessionMutation.Fact.Label -> {
                    if (!entriesById.containsKey(mutation.targetId)) {
                        invalid("references missing label target ${mutation.targetId}")
                    }
                    sequence = seq
                    if (mutation.label == null) labels.remove(mutation.targetId) else labels[mutation.targetId] = mutation.label
                }
            }
        }
    }

    /**
     * pi's findOpenOperations: the lane's unfinished operation starts,
     * newest first. Recovery uses `limit: 2` — zero results mean the lane is
     * idle, one means it is suspended, and two mean at least two operations
     * are open, which is corruption; further results provide no additional
     * recovery state.
     */
    fun findOpenOperations(lane: String, limit: Int? = null): List<LaneRecord.OperationStartedRecord> {
        if (limit != null && limit <= 0) {
            throw SessionDataException("limit must be a positive integer")
        }
        val openOperations = openOperationsByLane[lane]?.values?.toList()?.asReversed() ?: emptyList()
        return if (limit == null) openOperations else openOperations.take(limit)
    }

    private fun invalid(message: String): Nothing =
        throw SessionDataException("Invalid session mutation: $message")

    // ---- queries (pi's state.ts findEntries/findEntriesOnBranch/findRecords) ----

    /** pi's findEntries: filters, order (default newestFirst), limit, cursor. */
    fun findEntries(query: EntryQuery = EntryQuery()): List<SessionEntry> {
        assertValidLimit(query.limit)
        assertValidCursor(query.cursor?.afterSeq)
        val results = ArrayList<SessionEntry>()
        for (entry in ordered(entryList, query.order)) {
            if (!matchesEntryQuery(entry, query)) continue
            results.add(entry)
            if (results.size == query.limit) break
        }
        return results
    }

    /**
     * pi's findEntriesOnBranch (storage-level signature,
     * `EntryQuery & BranchBounds & { start: string }`): walks from
     * [BranchEntryQuery.start] toward the root, honoring stopAtId /
     * stopAtType (inclusive), filters, order, limit, and cursor.
     * oldestFirst returns root→start; newestFirst (default) start→root.
     */
    fun findEntriesOnBranch(query: BranchEntryQuery): List<SessionEntry> {
        assertValidLimit(query.limit)
        assertValidCursor(query.cursor?.afterSeq)
        val entryQuery = query.toEntryQuery()
        val results = ArrayList<SessionEntry>()
        if (query.order == EntryOrder.OLDEST_FIRST) {
            // Like pi: the oldestFirst scan walks the whole path unbounded;
            // bounds apply as an inclusive break after each entry.
            for (entry in walkToRoot(query.start).toList().asReversed()) {
                val reachedBound = entry.id == query.stopAtId || entry.entryType == query.stopAtType
                if (matchesEntryQuery(entry, entryQuery)) results.add(entry)
                if (reachedBound || results.size == query.limit) break
            }
        } else {
            for (entry in walkToRoot(query.start, query.stopAtId, query.stopAtType)) {
                if (matchesEntryQuery(entry, entryQuery)) results.add(entry)
                if (results.size == query.limit) break
            }
        }
        return results
    }

    /** pi's findRecords: lane/type/runId/operationKind filters, afterSeq, order, limit. */
    fun findRecords(query: RecordQuery = RecordQuery()): List<LaneRecord> {
        assertValidLimit(query.limit)
        assertValidCursor(query.afterSeq)
        val results = ArrayList<LaneRecord>()
        for (record in ordered(recordList, query.order)) {
            if (!matchesRecordQuery(record, query)) continue
            results.add(record)
            if (results.size == query.limit) break
        }
        return results
    }

    // ---- fork (pi's state.ts createForkMutations) ----

    /**
     * pi's createForkMutations: the mutation batch that seeds a forked
     * session's log, seq'd from 1. Tree scope copies every entry (oldest
     * first) plus all lane pointers; branch scope copies the root→target
     * path for a message-entry target ([ForkOptions.Branch.entryId]
     * defaults to the "main" lane leaf; position defaults to "at" when
     * entryId is omitted, "before" otherwise) and forks only the "main"
     * lane at the target. The name fact and the copied entries' label
     * facts follow. Entries are copied with rebound seq only — the payloads
     * are immutable values, so pi's structuredClone has no Kotlin
     * counterpart (documented divergence).
     *
     * @throws SessionDataException (pi: invalid_fork_target) when a branch
     * scope targets an entry that is not a message entry.
     */
    fun createForkMutations(options: ForkOptions): List<SessionMutation> {
        val copiedEntries: List<SessionEntry>
        val forkLanes: List<LanePointer>
        when (options) {
            is ForkOptions.Tree -> {
                copiedEntries = findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                forkLanes = getLanes()
            }
            is ForkOptions.Branch -> {
                val selectedEntryId = options.entryId ?: requireLane(LANE_MAIN)
                var targetId: String? = null
                if (selectedEntryId != null) {
                    val entry = entry(selectedEntryId)
                        ?: throw SessionDataException("Fork target is not a message entry: $selectedEntryId")
                    if (entry !is MessageEntry) {
                        throw SessionDataException("Fork target is not a message entry: $selectedEntryId")
                    }
                    val position = options.position
                        ?: if (options.entryId == null) ForkOptions.Branch.Position.AT else ForkOptions.Branch.Position.BEFORE
                    targetId = if (position == ForkOptions.Branch.Position.AT) entry.id else entry.parentId
                }
                copiedEntries = if (targetId == null) {
                    emptyList()
                } else {
                    findEntriesOnBranch(BranchEntryQuery(start = targetId, order = EntryOrder.OLDEST_FIRST))
                }
                forkLanes = listOf(LanePointer(LANE_MAIN, targetId))
            }
        }

        val mutations = ArrayList<SessionMutation>()
        var sequence = 1L
        for (sourceEntry in copiedEntries) {
            mutations.add(SessionMutation.Entry(lane = null, entry = sourceEntry.withSeq(sequence++)))
        }
        for (pointer in forkLanes) {
            mutations.add(SessionMutation.Lane(sequence++, pointer.lane, pointer.leafId))
        }
        if (name != null) {
            mutations.add(SessionMutation.Fact.Name(sequence++, name))
        }
        for (entry in copiedEntries) {
            val label = labels[entry.id]
            if (label != null) {
                mutations.add(SessionMutation.Fact.Label(sequence++, entry.id, label))
            }
        }
        return mutations
    }

    /** pi's walkToRoot: leaf→root walk with a cycle guard and inclusive bounds. */
    private fun walkToRoot(start: String, stopAtId: String? = null, stopAtType: EntryType? = null): Sequence<SessionEntry> = sequence {
        val visited = HashSet<String>()
        var current = entriesById[start]
            ?: throw SessionDataException("Entry not found: $start")
        while (true) {
            if (!visited.add(current.id)) {
                throw SessionDataException("Session branch contains a cycle at ${current.id}")
            }
            yield(current)
            if (current.id == stopAtId || current.entryType == stopAtType || current.parentId == null) break
            val parentId = current.parentId ?: return@sequence
            current = entriesById[parentId]
                ?: throw SessionDataException("Entry not found: $parentId")
        }
    }

    private fun <T> ordered(items: List<T>, order: EntryOrder?): Iterable<T> =
        if (order == EntryOrder.OLDEST_FIRST) items else items.asReversed()

    private fun matchesEntryQuery(entry: SessionEntry, query: EntryQuery): Boolean {
        val typeMatches = query.type == null || entry.entryType == query.type
        val customTypeMatches = query.customType == null ||
            (entry is CustomEntry && entry.customType == query.customType)
        val cursorMatches = query.cursor == null ||
            (if (query.order == EntryOrder.OLDEST_FIRST) entry.seq > query.cursor.afterSeq else entry.seq < query.cursor.afterSeq)
        return typeMatches && customTypeMatches && cursorMatches
    }

    private fun matchesRecordQuery(record: LaneRecord, query: RecordQuery): Boolean {
        val laneMatches = query.lane == null || record.lane == query.lane
        val typeMatches = query.type == null || record.recordType == query.type
        val runIdMatches = query.runId == null || when (record) {
            is LaneRecord.OperationStartedRecord -> record.id == query.runId
            is LaneRecord.AbortRequestedRecord -> record.runId == query.runId
            is LaneRecord.OperationFinishedRecord -> record.runId == query.runId
            // Records without an operation identity do not match (pi's `"runId" in record`).
            else -> false
        }
        val operationKindMatches = query.operationKind == null ||
            (record is LaneRecord.OperationStartedRecord && record.intent.kind == query.operationKind)
        val afterSeqMatches = query.afterSeq == null || record.seq > query.afterSeq
        return laneMatches && typeMatches && runIdMatches && operationKindMatches && afterSeqMatches
    }

    companion object {
        /** pi's default lane (state.ts lanes map seeded with `main`). */
        const val LANE_MAIN = "main"

        /** pi's assertValidLimit (invalid_query: limit must be a positive integer). */
        private fun assertValidLimit(limit: Int?) {
            if (limit != null && limit <= 0) {
                throw SessionDataException("limit must be a positive integer")
            }
        }

        /** pi's assertValidCursor (invalid_query: cursor sequence must be a non-negative integer). */
        private fun assertValidCursor(afterSeq: Long?) {
            if (afterSeq != null && afterSeq < 0) {
                throw SessionDataException("cursor sequence must be a non-negative integer")
            }
        }
    }
}

/** pi's SessionStats (session/types.ts), folded incrementally by [SessionState]. */
data class SessionStats(
    val messageCount: Int,
    val cachedTokens: Long,
    val uncachedTokens: Long,
    val totalTokens: Long,
    val costTotal: Double,
)
