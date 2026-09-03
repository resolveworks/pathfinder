package works.resolve.pathfinder.agent

/**
 * File-operation metadata carried by coding-agent compaction events.
 *
 * This small value crosses the classic-agent event boundary, so it lives in
 * the lower-level agent module while compaction behavior remains in
 * `packages/coding-agent`.
 */
data class CompactionDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)
