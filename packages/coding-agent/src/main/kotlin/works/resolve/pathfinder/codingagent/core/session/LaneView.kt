package works.resolve.pathfinder.codingagent.core.session

import kotlinx.serialization.json.JsonElement
import works.resolve.pathfinder.ai.Message

/**
 * Lane-scoped projection of an open session (pi's Session.view(lane)): reads
 * and writes go through the session's storage with [lane]'s leaf as the
 * default branch start and append target. Upstream, "main" returns the
 * session itself; here every lane goes through the same view.
 *
 * The UI stays single-lane — the chat screen only ever uses "main" through
 * [Session] — while the storage model carries lanes so pi-produced session
 * files replay with pi semantics.
 *
 * Divergence from pi: the write members are `suspend`, routed through the
 * [write] barrier supplied by [SessionStore], which serializes storage
 * writes at the store where pi serializes them inside the storage's `tail`
 * promise.
 */
class LaneView internal constructor(
    val lane: String,
    private val storage: JsonlSessionStorage,
    private val write: Writer
) {
    /** Serializes a write through the owning store. */
    internal interface Writer {
        suspend fun <T> write(block: (JsonlSessionStorage) -> T): T
    }

    /** The lane's current leaf; throws when the lane does not exist. */
    fun leafId(): String? = storage.leafId(lane)

    fun entry(id: String): SessionEntry? = storage.entry(id)

    fun stats(): SessionStats = storage.stats()

    fun name(): String? = storage.name()

    suspend fun setName(name: String?) = write.write { it.setName(name) }

    fun label(targetId: String): String? = storage.label(targetId)

    suspend fun setLabel(targetId: String, label: String?) = write.write {
        it.setLabel(targetId, label)
    }

    /** Query validation is state-level, as upstream. */
    fun findEntries(query: EntryQuery = EntryQuery()): List<SessionEntry> =
        storage.findEntries(query)

    /** [findEntries] capped at one result. */
    fun findEntry(query: EntryQuery = EntryQuery()): SessionEntry? =
        storage.findEntries(withResultLimit(query, 1)).firstOrNull()

    /** [BranchBounds.start] defaults to the lane's current leaf; an empty lane returns no entries. */
    fun findEntriesOnBranch(
        query: EntryQuery = EntryQuery(),
        stopAtType: EntryType? = null,
        stopAtId: String? = null
    ): List<SessionEntry> {
        val start = leafId() ?: return emptyList()
        return storage.findEntriesOnBranch(
            BranchEntryQuery(
                start = start,
                stopAtType = stopAtType,
                stopAtId = stopAtId,
                type = query.type,
                customType = query.customType,
                order = query.order,
                limit = query.limit,
                cursor = query.cursor
            )
        )
    }

    /** [findEntriesOnBranch] capped at one result. */
    fun findEntryOnBranch(
        query: EntryQuery = EntryQuery(),
        stopAtType: EntryType? = null,
        stopAtId: String? = null
    ): SessionEntry? =
        findEntriesOnBranch(withResultLimit(query, 1), stopAtType, stopAtId).firstOrNull()

    /** Appends [message] to this lane; returns the entry id. */
    suspend fun appendMessage(message: Message): String =
        write.write { it.appendMessage(message, lane) }

    suspend fun appendCustomEntry(customType: String, data: JsonElement? = null): String =
        write.write { it.appendCustomEntry(customType, data, lane) }

    companion object {
        /** Caps results without changing the caller's query (pi's queryEntries resultLimit). */
        private fun withResultLimit(query: EntryQuery, limit: Int): EntryQuery =
            if (query.limit == limit) query else query.copy(limit = limit)
    }
}
