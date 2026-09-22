package works.resolve.pathfinder.codingagent.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathUtilsTest {

    @Test
    fun `unicode space variants normalize to plain spaces`() {
        assertEquals(
            "/home/u/dir/sub dir/a b.txt",
            resolveToCwd("dir/sub\u00A0dir/a\u202Fb.txt", "/home/u")
        )
        assertEquals("/x y", resolveToCwd("/x\u2005y", "/w"))
    }

    @Test
    fun `leading at-prefix is stripped`() {
        assertEquals("/home/u/rel/file.txt", resolveToCwd("@rel/file.txt", "/home/u"))
        assertEquals("/home/u", resolveToCwd("@", "/home/u"))
    }

    @Test
    fun `file urls convert to decoded absolute paths`() {
        assertEquals("/home/u/My Docs/a.txt", resolveToCwd("file:///home/u/My%20Docs/a.txt", "/w"))
        assertEquals("/p/a", resolveToCwd("file://localhost/p%2Fa?q=1#frag", "/w"))
        assertEquals("/h/日.txt", resolveToCwd("file:///h/%E6%97%A5.txt", "/w"))
        assertEquals("/a/b", resolveToCwd("file:///a\\b", "/w"))
        assertEquals("/", resolveToCwd("file://", "/w"))
    }

    @Test
    fun `tilde stays a literal relative segment`() {
        // Divergence from pi: "~" expansion reads the local home directory,
        // the wrong machine over the operations seam.
        assertEquals("/home/u/~/notes", resolveToCwd("~/notes", "/home/u"))
    }

    @Test
    fun `normalizations apply in pi's order before joining`() {
        assertEquals("/w/rel p", resolveToCwd("@rel\u00A0p", "/w"))
        assertEquals("/abs p", resolveToCwd("file:///abs%20p", "/w"))
    }

    @Test
    fun `non-localhost file url hosts are rejected like node`() {
        assertFailsWith<IllegalArgumentException> { resolveToCwd("file://evil.com/x", "/w") }
    }

    @Test
    fun `malformed percent escapes are rejected`() {
        assertFailsWith<IllegalArgumentException> { resolveToCwd("file:///a%zz", "/w") }
        assertFailsWith<IllegalArgumentException> { resolveToCwd("file:///a%2", "/w") }
    }
}
