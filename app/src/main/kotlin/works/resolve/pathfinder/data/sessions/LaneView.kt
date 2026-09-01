package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.Message
import kotlinx.serialization.json.JsonElement

/**
 * Lane-scoped projection of an open session, porting pi's Session.view(lane)
 * (packages/agent/src/harness/session/session.ts:66): the returned
 * SessionTree reads and writes through the session's storage with [lane]'s
 * leaf as the default branch start and append target ("main" returns the
 * session itself upstream; here every lane goes through the same view).
 *
 * Product boundary (audit P1-1): Android's UI stays single-lane — the chat
 * screen only ever uses the "main" lane through [Session]. The storage model
 * carries lanes so pi-produced session files replay with pi semantics and
 * future work (paging, the reducer, branch UI) can address them.
 *
 * Divergence from pi's view object: the write members are `suspend` and are
 * routed through the [write] barrier supplied by [SessionStore] (its mutex +
 * IO dispatcher), because Pathfinder serializes storage writes at the store
 * where pi serializes them inside JsonlSessionStorage's `tail` promise.
 */
class LaneView internal constructor(
    val lane: String,
    private val storage: JsonlSessionStorage,
    private val write: Writer,
) {
    /** Serializes a write through the owning store (pi serializes inside the storage's `tail` promise). */
    internal interface Writer {
        suspend fun <T> write(block: (JsonlSessionStorage) -> T): T
    }

    /** pi's view getLeafId: the lane's current leaf; throws when the lane does not exist. */
    fun leafId(): String? = storage.leafId(lane)

    /** pi's view getEntry. */
    fun entry(id: String): SessionEntry? = storage.entry(id)

    /** pi's view getStats. */
    fun stats(): SessionStats = storage.stats()

    /** pi's view getName. */
    fun name(): String? = storage.name()

    /** pi's view setName. */
    suspend fun setName(name: String?) = write.write { it.setName(name) }

    /** pi's view getLabel. */
    fun label(targetId: String): String? = storage.label(targetId)

    /** pi's view setLabel. */
    suspend fun setLabel(targetId: String, label: String?) = write.write { it.setLabel(targetId, label) }

    /** pi's view findEntries (query validation is state-level, like upstream). */
    fun findEntries(query: EntryQuery = EntryQuery()): List<SessionEntry> = storage.findEntries(query)

    /** pi's view findEntry: [findEntries] capped at one result. */
    fun findEntry(query: EntryQuery = EntryQuery()): SessionEntry? =
        storage.findEntries(withResultLimit(query, 1)).firstOrNull()

    /**
     * pi's view findEntriesOnBranch: [BranchBounds.start] defaults to the
     * lane's current leaf; an empty lane returns no entries.
     */
    fun findEntriesOnBranch(
        query: EntryQuery = EntryQuery(),
        stopAtType: EntryType? = null,
        stopAtId: String? = null,
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
                cursor = query.cursor,
            ),
        )
    }

    /** pi's view findEntryOnBranch: [findEntriesOnBranch] capped at one result. */
    fun findEntryOnBranch(
        query: EntryQuery = EntryQuery(),
        stopAtType: EntryType? = null,
        stopAtId: String? = null,
    ): SessionEntry? = findEntriesOnBranch(withResultLimit(query, 1), stopAtType, stopAtId).firstOrNull()

    /** pi's view appendMessage: appends [message] to this lane, returning the entry id. */
    suspend fun appendMessage(message: Message): String = write.write { it.appendMessage(message, lane) }

    /** pi's view appendCustomEntry. */
    suspend fun appendCustomEntry(customType: String, data: JsonElement? = null): String =
        write.write { it.appendCustomEntry(customType, data, lane) }

    companion object {
        /** pi's queryEntries resultLimit: cap results without changing the caller's query. */
        private fun withResultLimit(query: EntryQuery, limit: Int): EntryQuery =
            if (query.limit == limit) query else query.copy(limit = limit)
    }
}
