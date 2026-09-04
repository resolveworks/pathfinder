package works.resolve.pathfinder.codingagent.core.utils

import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Usage

/**
 * All-zero usage — pi's `emptyUsage` (`harness/utils/usage.ts`, introduced
 * upstream after this mirror's pin); identical to the [Usage] defaults.
 */
fun emptyUsage(): Usage = Usage()

/**
 * Element-wise usage sum — pi's `addUsage` (`harness/utils/usage.ts`).
 *
 * Upstream keeps `cacheWrite1h`/`reasoning` undefined when absent on both
 * sides; pathfinder's [Usage] models them as non-nullable ints (0 =
 * unreported), so they are summed unconditionally.
 */
fun addUsage(left: Usage, right: Usage): Usage = Usage(
    input = left.input + right.input,
    output = left.output + right.output,
    cacheRead = left.cacheRead + right.cacheRead,
    cacheWrite = left.cacheWrite + right.cacheWrite,
    cacheWrite1h = left.cacheWrite1h + right.cacheWrite1h,
    reasoning = left.reasoning + right.reasoning,
    totalTokens = left.totalTokens + right.totalTokens,
    cost = left.cost + right.cost
)

private operator fun Cost.plus(other: Cost): Cost = Cost(
    input = input + other.input,
    output = output + other.output,
    cacheRead = cacheRead + other.cacheRead,
    cacheWrite = cacheWrite + other.cacheWrite,
    total = total + other.total
)
