package works.resolve.pathfinder.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ThinkingLevel ↔ ModelThinkingLevel mappers mirror pi's
 * `"off" | ThinkingLevel` union (types.ts:83-85): mapping up is total,
 * mapping down yields null for OFF.
 */
class ThinkingLevelMappersTest {

    @Test
    fun everyLevelMapsUpToTheSameNamedModelLevel() {
        for (level in ThinkingLevel.entries) {
            assertEquals(level.name, level.toModelThinkingLevel().name)
        }
    }

    @Test
    fun mappingDownYieldsNullOnlyForOff() {
        for (level in ModelThinkingLevel.entries) {
            val down = level.toThinkingLevelOrNull()
            if (level == ModelThinkingLevel.OFF) {
                assertNull(down)
            } else {
                assertEquals(level.name, down!!.name)
            }
        }
    }

    @Test
    fun roundTripPreservesNonOffLevels() {
        for (level in ThinkingLevel.entries) {
            assertEquals(level, level.toModelThinkingLevel().toThinkingLevelOrNull())
        }
    }
}
