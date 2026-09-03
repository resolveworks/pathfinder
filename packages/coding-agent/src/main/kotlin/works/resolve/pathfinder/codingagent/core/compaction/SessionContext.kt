package works.resolve.pathfinder.codingagent.core.compaction

import works.resolve.pathfinder.codingagent.core.session.ActiveToolsEntry
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.CustomEntry
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionEntry
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.StopReason

/**
 * Mirrors pi's `packages/agent/src/harness/session/context.ts` at pin
 * b8b873b98.
 *
 * Keep only the latest compaction entry and everything after it: everything
 * before it is already summarized into that entry. Upstream names this
 * `defaultContextEntryTransform`; its `buildContextEntries` is exactly this
 * transform plus optional caller-supplied `entryTransforms`
 * (`SessionContextBuildOptions`), which no pi caller passes at the pin —
 * so only the default selection is ported.
 *
 * Deferred-response scope (differences.md §5.1): the ported surface keeps
 * only the request side and the session-bookkeeping side of pi's deferred
 * tool/response machinery — the request-side split
 * (`splitDeferredTools`/`DeferredToolPlacement` in
 * `ai/utils/DeferredTools.kt`, driven by the adapters' mode selects),
 * `StopReason.DEFERRED` with the context drop
 * below, and the reducer's `write_deferred`/deferred-fetch failure
 * attribution. The fetch/cancel lifecycle is intentionally absent: pi's
 * `DeferredHandle` (a resumable-response handle carried on
 * `AssistantMessage.deferred`) plus `fetchDeferred`/`cancelDeferred` on the
 * Api/Models surface (`ai/src/types.ts`, `models.ts`, `lazy.ts`) exist so a
 * host can resume a deferred response later; no pathfinder adapter produces
 * a deferred response and no host surface exists to fetch one, so there is
 * nothing to resume — the drop below is the only observable consequence in
 * this port. Port the handle plus fetch/cancel seam if a provider that
 * pathfinder supports end to end starts returning deferred responses.
 */
fun defaultContextEntryTransform(pathEntries: List<SessionEntry>): List<SessionEntry> {
    var compactionIndex = -1
    for (index in pathEntries.indices.reversed()) {
        if (pathEntries[index] is CompactionEntry) {
            compactionIndex = index
            break
        }
    }
    return if (compactionIndex == -1) pathEntries.toList() else pathEntries.drop(compactionIndex)
}

/**
 * Pathfinder-specific helper with no upstream twin at the pin: AgentSession's
 * compaction guard uses it to skip stale pre-compaction usage.
 */
fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is CompactionEntry) return entry
    }
    return null
}

private fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is MessageEntry -> {
        // Deferred assistant messages drop from context: a deferred response
        // is not final and its content is not authoritative.
        val assistant = entry.message as? AssistantMessage
        if (assistant != null && assistant.stopReason == StopReason.DEFERRED) {
            emptyList()
        } else {
            listOf(entry.message)
        }
    }
    is CompactionEntry -> listOf(createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)) +
        entry.retainedTail
    // Upstream guards summary truthiness; with a non-null summary here, that
    // reduces to excluding the empty string.
    is BranchSummaryEntry -> if (entry.summary.isNotEmpty()) {
        listOf(createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp))
    } else {
        emptyList()
    }
    // Upstream routes `custom` entries through per-type projectors; pathfinder
    // has no custom-entry producer, so nothing projects.
    is ModelChangeEntry, is ThinkingLevelEntry, is ActiveToolsEntry, is CustomEntry -> emptyList()
}

/**
 * Mirrors upstream `buildSessionContext` at the pin, message-list half only.
 *
 * Divergences from the upstream shape, all documented seams:
 * - Upstream also returns branch state (`thinkingLevel`, `model`,
 *   `activeToolNames`) derived from the FULL pre-compaction path
 *   (`deriveSessionContextState(pathEntries)`). Pathfinder splits that fold
 *   into [works.resolve.pathfinder.codingagent.core.session.Conversation.effectiveConfiguration],
 *   which iterates the same full active path — see SessionContextTest for the
 *   pinned split behavior.
 * - Upstream's options (`SessionContextBuildOptions` `entryTransforms`/
 *   `entryProjectors`) are not ported: no pi caller passes them at the pin
 *   and pathfinder has no custom-entry producer to project. Upstream's
 *   exported `buildContextEntries` reduces to [defaultContextEntryTransform]
 *   without them.
 * - Only deferred assistant messages drop from context. Upstream's later
 *   `isContextMessage` (which also drops error/aborted assistants and
 *   filters the retained tail) is post-pin drift; at the pin error/aborted
 *   assistants stay in context, and the retained tail passes through
 *   verbatim.
 */
fun buildSessionContext(pathEntries: List<SessionEntry>): List<Message> =
    defaultContextEntryTransform(pathEntries).flatMap(::sessionEntryToContextMessages)
