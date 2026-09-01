package works.resolve.pathfinder.data.sessions

/**
 * Replay state for a session's mutation log, porting pi's SessionState
 * (packages/agent/src/harness/session/state.ts): folds mutations in order,
 * enforcing the log invariants — consecutive 1-based seq, unused ids,
 * existing parents/lanes/label targets, and lane chaining for lane-addressed
 * entry mutations.
 *
 * Divergences (scope of this port, per the session-parity audit P0-1/P0-2):
 * - pi throws SessionError("invalid_entry", "Invalid session mutation: …");
 *   Pathfinder's module exception is [SessionDataException] with the same
 *   message text.
 * - Records are structurally applied (seq, id, lane validation) and kept in
 *   a list, but the open-operations map and usage-stats fold are not ported
 *   yet (no record producers; P0-3 rider).
 * - Queries (findEntries/EntryQuery cursors, RecordQuery, getLog) and
 *   createForkMutations are not ported; Pathfinder reads the whole replayed
 *   state instead (P1-2/P1-5 riders build on this).
 */
internal class SessionState {
    private var sequence = 0L
    private val usedIds = HashSet<String>()
    private val entryList = ArrayList<SessionEntry>()
    private val entriesById = HashMap<String, SessionEntry>()
    private val recordList = ArrayList<SessionRecord>()
    private val lanes = LinkedHashMap<String, String?>().apply { put(LANE_MAIN, null) }
    private val labels = HashMap<String, String>()

    /** Latest-wins session name fact (pi's `name` state). */
    var name: String? = null
        private set

    /** Entries in append (seq) order; defensive copy. */
    fun entries(): List<SessionEntry> = entryList.toList()

    /** Records in append (seq) order; defensive copy. */
    fun records(): List<SessionRecord> = recordList.toList()

    /** Lane pointers in insertion order (pi's getLanes). */
    fun lanes(): Map<String, String?> = LinkedHashMap(lanes)

    fun entry(id: String): SessionEntry? = entriesById[id]

    fun label(id: String): String? = labels[id]

    /** Number of message entries (pi's SessionStats.messageCount). */
    fun messageCount(): Int = entryList.count { it is MessageEntry }

    val nextSequence: Long
        get() = sequence + 1

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
     * parent/label-target existence).
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
            }
            is SessionMutation.Record -> {
                if (!lanes.containsKey(mutation.record.lane)) {
                    invalid("references missing lane ${mutation.record.lane}")
                }
                if (mutation.record.id in usedIds) invalid("contains duplicate id ${mutation.record.id}")
                sequence = seq
                usedIds.add(mutation.record.id)
                recordList.add(mutation.record)
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

    private fun invalid(message: String): Nothing =
        throw SessionDataException("Invalid session mutation: $message")

    companion object {
        /** pi's default lane (state.ts lanes map seeded with `main`). */
        const val LANE_MAIN = "main"
    }
}
