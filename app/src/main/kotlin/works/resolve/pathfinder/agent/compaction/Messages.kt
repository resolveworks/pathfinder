package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.UserMessage

/**
 * Partial twin of pi's `packages/agent/src/harness/messages.ts` at pin
 * b8b873b98: only `createCompactionSummaryMessage`/
 * `createBranchSummaryMessage` and the four prefix/suffix constants are
 * ported.
 *
 * pi's harness synthesizes `compactionSummary` and `branchSummary`
 * agent-message roles that `convertToLlm` later projects to user messages
 * wrapped (verbatim) in the prefix/suffix constants below. Pathfinder
 * cannot extend the sealed core [Message] roles (that would touch
 * out-of-scope exhaustive dispatch in ai/utils/ui), so each role is
 * collapsed into its projection: the create functions here return the
 * wrapped user message directly, and `convertToLlm` is omitted — it is the
 * identity for pathfinder messages, and compaction callers already hold
 * LLM-ready messages.
 *
 * The remaining upstream surface is deliberately omitted:
 * `BashExecutionMessage`/`bashExecutionToText` (pathfinder has no bash
 * surface) and `CustomMessage`/`createCustomMessage` (no custom-message
 * producer; `CustomEntry` data exists for wire replay only and projects
 * nothing into context).
 */

const val COMPACTION_SUMMARY_PREFIX =
    "The conversation history before this point was compacted into the following summary:\n" +
        "\n" +
        "<summary>\n"

const val COMPACTION_SUMMARY_SUFFIX =
    "\n" +
        "</summary>"

const val BRANCH_SUMMARY_PREFIX =
    "The following is a summary of a branch that this conversation came back from:\n" +
        "\n" +
        "<summary>\n"

const val BRANCH_SUMMARY_SUFFIX = "</summary>"

fun createCompactionSummaryMessage(
    summary: String,
    tokensBefore: Int,
    timestamp: Long,
): Message = UserMessage(
    content = listOf(TextContent(COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX)),
    timestamp = timestamp,
)

/**
 * Upstream accepts a string timestamp and converts; pathfinder entries
 * carry epoch millis already.
 */
fun createBranchSummaryMessage(
    summary: String,
    fromId: String,
    timestamp: Long,
): Message = UserMessage(
    content = listOf(TextContent(BRANCH_SUMMARY_PREFIX + summary + BRANCH_SUMMARY_SUFFIX)),
    timestamp = timestamp,
)
