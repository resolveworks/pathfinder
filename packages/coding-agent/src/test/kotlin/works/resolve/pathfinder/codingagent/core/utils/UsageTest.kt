package works.resolve.pathfinder.codingagent.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Usage

/** pi usage-totals.test.ts: a combined total is provider-reported only when every part is. */
class UsageTest {

    private fun usage(total: Double, totalFromProvider: Boolean? = null): Usage = Usage(
        input = 1,
        output = 1,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 2,
        cost = Cost(
            input = 0.0,
            output = 0.0,
            cacheRead = 0.0,
            cacheWrite = 0.0,
            total = total,
            totalFromProvider = totalFromProvider ?: false
        )
    )

    @Test
    fun `marks the combined total as provider-reported only when both totals are`() {
        val providerAndProvider = addUsage(usage(1.0, true), usage(2.0, true)).cost
        assertEquals(3.0, providerAndProvider.total)
        assertTrue(providerAndProvider.totalFromProvider)

        val providerAndCatalog = addUsage(usage(1.0, true), usage(2.0)).cost
        assertEquals(3.0, providerAndCatalog.total)
        assertFalse(providerAndCatalog.totalFromProvider)

        val catalogAndProvider = addUsage(usage(1.0), usage(2.0, true)).cost
        assertEquals(3.0, catalogAndProvider.total)
        assertFalse(catalogAndProvider.totalFromProvider)

        val catalogAndCatalog = addUsage(usage(1.0), usage(2.0)).cost
        assertEquals(3.0, catalogAndCatalog.total)
        assertFalse(catalogAndCatalog.totalFromProvider)
    }
}
