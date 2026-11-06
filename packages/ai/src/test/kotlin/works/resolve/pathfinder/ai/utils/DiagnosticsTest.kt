package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsTest {

    private class EmptyMessageException : Exception()

    @Test
    fun `falls back to the class name when a throwable has no message`() {
        val error = EmptyMessageException()
        assertEquals("EmptyMessageException", extractDiagnosticError(error).message)
        assertEquals("EmptyMessageException", formatThrownValue(error))
    }

    @Test
    fun `falls back to the class name when a throwable has an empty message`() {
        val error = IllegalStateException("")
        assertEquals("IllegalStateException", extractDiagnosticError(error).message)
        assertEquals("IllegalStateException", formatThrownValue(error))
    }
}
