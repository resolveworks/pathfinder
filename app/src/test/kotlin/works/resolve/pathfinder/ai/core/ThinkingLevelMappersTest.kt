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

    /**
     * The wire names are pi's level strings (the persisted
     * thinking_level_change entry value and settings value); decode is an
     * exhaustive when with a null default, never valueOf on stored input.
     */
    @Test
    fun wireNamesRoundTripAndUnknownWireDecodesToNull() {
        val piWireNames = mapOf(
            ModelThinkingLevel.OFF to "off",
            ModelThinkingLevel.MINIMAL to "minimal",
            ModelThinkingLevel.LOW to "low",
            ModelThinkingLevel.MEDIUM to "medium",
            ModelThinkingLevel.HIGH to "high",
            ModelThinkingLevel.XHIGH to "xhigh",
            ModelThinkingLevel.MAX to "max",
        )
        for ((level, wire) in piWireNames) {
            assertEquals(wire, level.wire)
            assertEquals(level, modelThinkingLevelFromWire(wire))
        }
        assertNull(modelThinkingLevelFromWire("ultra"))
        assertNull(modelThinkingLevelFromWire(""))
    }
}
