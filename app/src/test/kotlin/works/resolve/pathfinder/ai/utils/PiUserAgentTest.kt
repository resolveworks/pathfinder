package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provenance test for getPiUserAgent (port of pi's
 * packages/ai/src/utils/pi-user-agent.ts): `pi (${platform} ${release}; ${arch})`.
 */
class PiUserAgentTest {

    @Test
    fun `has pi user agent shape`() {
        val ua = getPiUserAgent()
        assertTrue(ua.startsWith("pi (android "), ua)
        assertTrue(ua.endsWith(")"), ua)
        // platform; release; arch — exactly two ';'-separated fields.
        val fields = ua.removePrefix("pi (").removeSuffix(")").split("; ")
        assertEquals(2, fields.size, ua)
    }
}
