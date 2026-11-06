package works.resolve.pathfinder.codingagent.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    @Test
    fun `sliceHead and sliceTail never split a surrogate pair`() {
        val text = "a\uD83D\uDE00b"

        assertEquals("a", sliceHead(text, 2))
        assertEquals("a\uD83D\uDE00", sliceHead(text, 3))
        assertEquals("\uD83D\uDE00b", sliceTail(text, 3))
        assertEquals("b", sliceTail(text, 2))
    }

    @Test
    fun `slice at the exact limit keeps the whole text`() {
        val text = "a\uD83D\uDE00"

        assertEquals(text, sliceHead(text, text.length))
        assertEquals(text, sliceTail(text, text.length))
        assertEquals(text, sliceHead(text, text.length + 1))
        assertEquals(text, sliceTail(text, text.length + 1))
    }
}
