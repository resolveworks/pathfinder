package works.resolve.pathfinder.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
