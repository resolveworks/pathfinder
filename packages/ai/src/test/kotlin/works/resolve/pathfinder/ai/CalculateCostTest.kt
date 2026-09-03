package works.resolve.pathfinder.ai

import works.resolve.pathfinder.ai.calculateCost
import kotlin.test.Test
import kotlin.test.assertEquals

class CalculateCostTest {

    private fun model(cost: ModelCost = ModelCost()) = Model(
        id = "m",
        name = "M",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://example.com",
        cost = cost,
    )

    @Test
    fun `base math prices each component at per-million rates`() {
        val cost = calculateCost(
            model(ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75)),
            Usage(input = 100, output = 50, cacheRead = 40, cacheWrite = 10),
        )
        assertEquals(100 * 3.0 / 1_000_000, cost.input, 1e-12)
        assertEquals(50 * 15.0 / 1_000_000, cost.output, 1e-12)
        assertEquals(40 * 0.3 / 1_000_000, cost.cacheRead, 1e-12)
        assertEquals(10 * 3.75 / 1_000_000, cost.cacheWrite, 1e-12)
        assertEquals(cost.input + cost.output + cost.cacheRead + cost.cacheWrite, cost.total, 1e-12)
    }

    @Test
    fun `no 1h split reduces to plain cacheWrite pricing`() {
        val plain = calculateCost(
            model(ModelCost(input = 5.0, cacheWrite = 6.25)),
            Usage(cacheWrite = 1_000_000),
        )
        assertEquals(6.25, plain.cacheWrite, 1e-10)
    }

    @Test
    fun `1h cache writes are priced at 2x base input`() {
        // claude-opus-4-8 rates; cacheWrite is the 5m tier.
        val cost = calculateCost(
            model(ModelCost(input = 5.0, cacheWrite = 6.25)),
            Usage(cacheWrite = 1_000_000, cacheWrite1h = 400_000),
        )
        assertEquals(7.75, cost.cacheWrite, 1e-10)
    }

    @Test
    fun `tier applies when total input usage strictly exceeds the threshold`() {
        val cost = calculateCost(
            model(
                ModelCost(
                    input = 3.0,
                    output = 15.0,
                    tiers = listOf(
                        ModelCostTier(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25, inputTokensAbove = 100_000),
                    ),
                ),
            ),
            // input + cacheRead + cacheWrite = 100_001 > 100_000
            Usage(input = 50_000, output = 10, cacheRead = 25_000, cacheWrite = 25_001),
        )
        assertEquals(50_000 * 1.0 / 1_000_000, cost.input, 1e-12)
        assertEquals(10 * 5.0 / 1_000_000, cost.output, 1e-12)
        assertEquals(25_000 * 0.1 / 1_000_000, cost.cacheRead, 1e-12)
        assertEquals(25_001 * 1.25 / 1_000_000, cost.cacheWrite, 1e-12)
    }

    @Test
    fun `threshold boundary is exclusive`() {
        val cost = calculateCost(
            model(
                ModelCost(
                    input = 3.0,
                    tiers = listOf(ModelCostTier(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25, inputTokensAbove = 100)),
                ),
            ),
            Usage(input = 100),
        )
        assertEquals(100 * 3.0 / 1_000_000, cost.input, 1e-12)
    }

    @Test
    fun `highest matching tier wins and applies to the full request`() {
        val cost = calculateCost(
            model(
                ModelCost(
                    input = 3.0,
                    tiers = listOf(
                        ModelCostTier(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25, inputTokensAbove = 100),
                        ModelCostTier(input = 0.5, output = 2.5, cacheRead = 0.05, cacheWrite = 0.625, inputTokensAbove = 1_000),
                        ModelCostTier(input = 0.1, output = 0.5, cacheRead = 0.01, cacheWrite = 0.125, inputTokensAbove = 10_000_000),
                    ),
                ),
            ),
            Usage(input = 1_500, output = 500),
        )
        assertEquals(1_500 * 0.5 / 1_000_000, cost.input, 1e-12)
        assertEquals(500 * 2.5 / 1_000_000, cost.output, 1e-12)
    }

    @Test
    fun `no tiers passes base rates through`() {
        val cost = calculateCost(
            model(ModelCost(input = 3.0, output = 15.0)),
            Usage(input = 10, output = 20),
        )
        assertEquals(10 * 3.0 / 1_000_000, cost.input, 1e-12)
        assertEquals(20 * 15.0 / 1_000_000, cost.output, 1e-12)
        assertEquals(cost.input + cost.output, cost.total, 1e-12)
    }
}
