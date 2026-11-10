package works.resolve.pathfinder.codingagent.core.utils

import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Usage

/**
 * Element-wise usage sum — pi's `combineUsage` (classic `core/usage-totals.ts`).
 *
 * Upstream keeps `cacheWrite1h`/`reasoning` undefined when absent on both
 * sides; pathfinder's [Usage] models them as non-nullable ints (0 =
 * unreported), so they are summed unconditionally. A combined cost's
 * `totalFromProvider` is true only when every part is provider-reported.
 */
fun combineUsage(left: Usage, right: Usage): Usage = Usage(
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
    total = total + other.total,
    // A sum is provider-reported only when every part is.
    totalFromProvider = totalFromProvider && other.totalFromProvider
)
