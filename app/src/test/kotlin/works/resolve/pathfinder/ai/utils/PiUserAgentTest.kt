package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provenance test for getPiUserAgent (port of pi's
 * packages/ai/src/utils/pi-user-agent.ts): `pi (${platform} ${release}; ${arch})`
 * with the product token deliberately `pathfinder` (owner decision — the
 * User-Agent identifies the client, and Pathfinder does not misattribute its
 * traffic to pi).
 */
class PiUserAgentTest {

    @Test
    fun `has pi user agent shape with the pathfinder product token`() {
        val ua = getPiUserAgent()
        assertTrue(ua.startsWith("pathfinder (android "), ua)
        assertTrue(ua.endsWith(")"), ua)
        // platform; release; arch — exactly two ';'-separated fields.
        val fields = ua.removePrefix("pathfinder (").removeSuffix(")").split("; ")
        assertEquals(2, fields.size, ua)
    }
}
