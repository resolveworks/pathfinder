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
 *   message text (invalid_query → the same exception on findOpenOperations).
 * - Entry/record queries (findEntries/EntryQuery cursors, RecordQuery,
 *   getLog) and createForkMutations are not ported; Pathfinder reads the
 *   whole replayed state instead (P1-2/P1-5 riders build on this).
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
    fun lanes(): Map<String, String?> = LinkedHashMap(lanes)

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

    companion object {
        /** pi's default lane (state.ts lanes map seeded with `main`). */
        const val LANE_MAIN = "main"
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
