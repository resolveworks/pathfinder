package works.resolve.pathfinder.data.sessions

/**
 * Replay state for a session's mutation log: folds mutations in order,
 * enforcing the log invariants — consecutive 1-based seq, unused ids,
 * existing parents/lanes/label targets, and lane chaining for
 * lane-addressed entry mutations.
 *
 * Divergences from pi: fork copies entries by rebinding seq only (Kotlin
 * payloads are immutable values; pi structuredClones them), and records
 * are validated only for lane existence and unused id — payload references
 * are never validated, so a record may precede the entries it references
 * in seq order (see [LaneRecord]).
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
    private val log = ArrayList<LogItem>()

    private var statsMessageCount = 0
    private var statsCachedTokens = 0L
    private var statsUncachedTokens = 0L
    private var statsTotalTokens = 0L
    private var statsCostTotal = 0.0

    /** Latest-wins session name fact. */
    var name: String? = null
        private set

    /** Entries in append (seq) order; defensive copy. */
    fun entries(): List<SessionEntry> = entryList.toList()

    /** Records in append (seq) order; defensive copy. */
    fun records(): List<LaneRecord> = recordList.toList()

    /** Lane pointers in insertion order. */
    fun getLanes(): List<LanePointer> = lanes.map { (lane, leafId) -> LanePointer(lane, leafId) }

    fun entry(id: String): SessionEntry? = entriesById[id]

    fun label(id: String): String? = labels[id]

    val nextSequence: Long
        get() = sequence + 1

    fun stats(): SessionStats = SessionStats(
        messageCount = statsMessageCount,
        cachedTokens = statsCachedTokens,
        uncachedTokens = statsUncachedTokens,
        totalTokens = statsTotalTokens,
        costTotal = statsCostTotal,
    )

    fun messageCount(): Int = statsMessageCount

    /** The lane's current leaf; throws when the lane does not exist. */
    fun requireLane(lane: String): String? =
        if (lanes.containsKey(lane)) {
            lanes[lane]
        } else {
            throw SessionError(SessionErrorCode.INVALID_LANE, "Lane not found: $lane")
        }

    /** Throws when [id] is already used by an entry or record. */
    fun validateUnusedId(id: String) {
        if (id in usedIds) throw SessionError(SessionErrorCode.ALREADY_EXISTS, "Session id already exists: $id")
    }

    /** Throws when [lane] already exists. */
    fun validateNewLane(lane: String) {
        if (lanes.containsKey(lane)) throw SessionError(SessionErrorCode.ALREADY_EXISTS, "Lane already exists: $lane")
    }

    /** Throws when [targetId] is not an existing entry. */
    fun validateTarget(targetId: String?) {
        if (targetId != null && !entriesById.containsKey(targetId)) {
            throw SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: $targetId")
        }
    }

    /**
     * Folds [mutation] into the state: the seq must be exactly the next
     * consecutive number, then per-kind checks run (duplicate ids, lane
     * existence, lane chaining, parent/label-target existence). Records
     * track each lane's open operations — operation_started opens,
     * operation_finished closes by runId; abort_requested does not close —
     * and usage records accumulate into [SessionStats].
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
                log.add(LogItem.Entry(seq, mutation.entry))
            }
            is SessionMutation.Record -> {
                val record = mutation.record
                if (!lanes.containsKey(record.lane)) invalid("references missing lane ${record.lane}")
                if (record.id in usedIds) invalid("contains duplicate id ${record.id}")
                sequence = seq
                usedIds.add(record.id)
                recordList.add(record)
                log.add(LogItem.Record(seq, record))
                when (record) {
                    is LaneRecord.OperationStartedRecord ->
                        openOperationsByLane.getOrPut(record.lane) { LinkedHashMap() }[record.id] = record
                    is LaneRecord.OperationFinishedRecord ->
                        openOperationsByLane[record.lane]?.remove(record.runId)
                    is LaneRecord.UsageRecord -> {
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
                log.add(LogItem.Lane(seq, mutation.lane, mutation.leafId))
            }
            is SessionMutation.Fact -> when (mutation) {
                is SessionMutation.Fact.Name -> {
                    sequence = seq
                    name = mutation.name
                    log.add(LogItem.FactName(seq, mutation.name))
                }
                is SessionMutation.Fact.Label -> {
                    if (!entriesById.containsKey(mutation.targetId)) {
                        invalid("references missing label target ${mutation.targetId}")
                    }
                    sequence = seq
                    if (mutation.label == null) labels.remove(mutation.targetId) else labels[mutation.targetId] = mutation.label
                    log.add(LogItem.FactLabel(seq, mutation.targetId, mutation.label))
                }
            }
        }
    }

    /** Log items after [afterSeq] (exclusive), oldest first, up to [limit]. */
    fun getLog(afterSeq: Long? = null, limit: Int? = null): List<LogItem> {
        if (limit != null && limit <= 0) {
            throw SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer")
        }
        if (afterSeq != null && afterSeq < 0) {
            throw SessionError(SessionErrorCode.INVALID_QUERY, "cursor sequence must be a non-negative integer")
        }
        val results = ArrayList<LogItem>()
        for (item in log) {
            if (afterSeq != null && item.seq <= afterSeq) continue
            results.add(item)
            if (results.size == limit) break
        }
        return results
    }

    /**
     * The lane's unfinished operation starts, newest first. Recovery uses
     * `limit: 2`: zero results mean the lane is idle, one means it is
     * suspended, and two mean at least two operations are open, which is
     * corruption; further results provide no additional recovery state.
     */
    fun findOpenOperations(lane: String, limit: Int? = null): List<LaneRecord.OperationStartedRecord> {
        if (limit != null && limit <= 0) {
            throw SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer")
        }
        val openOperations = openOperationsByLane[lane]?.values?.toList()?.asReversed() ?: emptyList()
        return if (limit == null) openOperations else openOperations.take(limit)
    }

    private fun invalid(message: String): Nothing =
        throw SessionError(SessionErrorCode.INVALID_ENTRY, "Invalid session mutation: $message")

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
     * Walks from [BranchEntryQuery.start] toward the root, honoring
     * stopAtId/stopAtType as inclusive bounds alongside filters, order,
     * limit, and cursor. oldestFirst returns root→start; newestFirst
     * (default) start→root.
     */
    fun findEntriesOnBranch(query: BranchEntryQuery): List<SessionEntry> {
        assertValidLimit(query.limit)
        assertValidCursor(query.cursor?.afterSeq)
        val entryQuery = query.toEntryQuery()
        val results = ArrayList<SessionEntry>()
        if (query.order == EntryOrder.OLDEST_FIRST) {
            // Like pi: the oldestFirst scan walks the whole path unbounded;
            // bounds apply as an inclusive break after each entry.
            for (entry in walkToRoot(::entry, query.start).toList().asReversed()) {
                val reachedBound = entry.id == query.stopAtId || entry.entryType == query.stopAtType
                if (matchesEntryQuery(entry, entryQuery)) results.add(entry)
                if (reachedBound || results.size == query.limit) break
            }
        } else {
            for (entry in walkToRoot(::entry, query.start, query.stopAtId, query.stopAtType)) {
                if (matchesEntryQuery(entry, entryQuery)) results.add(entry)
                if (results.size == query.limit) break
            }
        }
        return results
    }

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

    /**
     * The mutation batch that seeds a forked session's log, seq'd from 1.
     * Tree scope copies every entry (oldest first) plus all lane pointers;
     * branch scope copies the root→target path for a message-entry target
     * and forks only the "main" lane at the target
     * ([ForkOptions.Branch.entryId] defaults to the main lane's leaf;
     * position defaults to "at" when entryId is omitted, "before"
     * otherwise). The name fact and the copied entries' label facts follow.
     *
     * @throws SessionError [SessionErrorCode.INVALID_FORK_TARGET] when a
     * branch scope targets an entry that is not a message entry.
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
                    if (entry !is MessageEntry) {
                        throw SessionError(
                            SessionErrorCode.INVALID_FORK_TARGET,
                            "Fork target is not a message entry: $selectedEntryId",
                        )
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
            // Records without a runId do not match.
            else -> false
        }
        val operationKindMatches = query.operationKind == null ||
            (record is LaneRecord.OperationStartedRecord && record.intent.kind == query.operationKind)
        val afterSeqMatches = query.afterSeq == null || record.seq > query.afterSeq
        return laneMatches && typeMatches && runIdMatches && operationKindMatches && afterSeqMatches
    }

    companion object {
        const val LANE_MAIN = "main"

        private fun assertValidLimit(limit: Int?) {
            if (limit != null && limit <= 0) {
                throw SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer")
            }
        }

        private fun assertValidCursor(afterSeq: Long?) {
            if (afterSeq != null && afterSeq < 0) {
                throw SessionError(SessionErrorCode.INVALID_QUERY, "cursor sequence must be a non-negative integer")
            }
        }
    }
}

/**
 * Leaf→root walk with a cycle guard and inclusive bounds — pi's private
 * `SessionState.walkToRoot` (`harness/session/state.ts`). Upstream exposes
 * the walk only through `findEntriesOnBranch`, and its branch summarization
 * goes through that query; pathfinder's branch summarization walks a
 * [Conversation], which has no query surface, so the walk is shared from
 * here instead of duplicated per caller.
 */
internal fun walkToRoot(
    entryById: (String) -> SessionEntry?,
    start: String,
    stopAtId: String? = null,
    stopAtType: EntryType? = null,
): Sequence<SessionEntry> = sequence {
    val visited = HashSet<String>()
    var current = entryById(start)
        ?: throw SessionError(SessionErrorCode.NOT_FOUND, "Entry not found: $start")
    while (true) {
        if (!visited.add(current.id)) {
            throw SessionError(SessionErrorCode.INVALID_ENTRY, "Session branch contains a cycle at ${current.id}")
        }
        yield(current)
        if (current.id == stopAtId || current.entryType == stopAtType || current.parentId == null) break
        val parentId = current.parentId ?: return@sequence
        current = entryById(parentId)
            ?: throw SessionError(SessionErrorCode.INVALID_ENTRY, "Entry not found: $parentId")
    }
}

/** Session stats, folded incrementally from message entries and usage records by [SessionState]. */
data class SessionStats(
    val messageCount: Int,
    val cachedTokens: Long,
    val uncachedTokens: Long,
    val totalTokens: Long,
    val costTotal: Double,
)
