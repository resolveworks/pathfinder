package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage

/**
 * Reductions from pi's `packages/agent/src/harness/messages.ts`, limited to
 * the compaction-summary message machinery needed by the compaction port.
 *
 * Upstream synthesizes a distinct `compactionSummary` agent-message role
 * (packages/agent/src/types.ts CustomAgentMessages,
 * `createCompactionSummaryMessage`), which `convertToLlm` later projects to
 * a user message wrapped in [COMPACTION_SUMMARY_PREFIX]/
 * [COMPACTION_SUMMARY_SUFFIX]. Pathfinder has no agent-message union
 * layered over the core `Message` hierarchy (and cannot extend the sealed
 * core roles without touching out-of-scope exhaustive dispatch in
 * ai/utils/ui), so the port collapses the agent role into its
 * `convertToLlm` projection: [createCompactionSummaryMessage] returns the
 * wrapped user message directly. `convertToLlm` itself becomes the identity
 * for pathfinder messages and is therefore omitted; compaction callers
 * already hold LLM-ready messages.
 */

/** Wrapping prefix for a compaction summary in LLM context (messages.ts `COMPACTION_SUMMARY_PREFIX`), verbatim. */
const val COMPACTION_SUMMARY_PREFIX =
    "The conversation history before this point was compacted into the following summary:\n" +
        "\n" +
        "<summary>\n"

/** Wrapping suffix for a compaction summary in LLM context (messages.ts `COMPACTION_SUMMARY_SUFFIX`), verbatim. */
const val COMPACTION_SUMMARY_SUFFIX =
    "\n" +
        "</summary>"

/**
 * Build the context message a harness compaction entry contributes
 * (messages.ts `createCompactionSummaryMessage`, projected as its
 * convertToLlm form — see file docs).
 */
fun createCompactionSummaryMessage(
    summary: String,
    tokensBefore: Int,
    timestamp: Long,
): Message = UserMessage(
    content = listOf(TextContent(COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX)),
    timestamp = timestamp,
)
