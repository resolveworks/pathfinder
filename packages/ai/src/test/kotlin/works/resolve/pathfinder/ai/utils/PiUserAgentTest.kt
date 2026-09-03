package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiUserAgentTest {

    @Test
    fun `has pi user agent shape with the pathfinder product token`() {
        val ua = getPiUserAgent()
        assertTrue(ua.startsWith("pathfinder (android "), ua)
        assertTrue(ua.endsWith(")"), ua)
        val fields = ua.removePrefix("pathfinder (").removeSuffix(")").split("; ")
        assertEquals(2, fields.size, ua)
    }
}
