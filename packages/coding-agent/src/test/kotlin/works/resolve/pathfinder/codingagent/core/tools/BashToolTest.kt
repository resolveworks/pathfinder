package works.resolve.pathfinder.codingagent.core.tools

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent

class BashToolTest {

    private class FakeBashOperations(
        var exitCode: Int? = 0,
        var output: ByteArray = "out".toByteArray(),
        var failure: Throwable? = null
    ) : BashOperations {
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Double?>()

        override suspend fun exec(
            command: String,
            cwd: String,
            onData: (ByteArray) -> Unit,
            timeout: Double?
        ): Int? {
            commands.add(command)
            timeouts.add(timeout)
            onData(output)
            failure?.let { throw it }
            return exitCode
        }
    }

    private fun bashTool(operations: FakeBashOperations): AgentTool = createBashTool(
        "/tmp",
        BashToolOptions(
            operations,
            tempDir = createTempDirectory("pathfinder-bash-test").toAbsolutePath().toString()
        )
    )

    private fun arguments(command: String, timeout: Double? = null): JsonObject = buildJsonObject {
        put("command", command)
        timeout?.let { put("timeout", it) }
    }

    private fun text(result: AgentToolResult): String =
        (result.content.single() as TextContent).text

    @Test
    fun `forwards the raw timeout without validating it`() = runTest {
        val operations = FakeBashOperations()
        val result = bashTool(operations).execute("call-1", arguments("ls", timeout = 0.0)) {}
        assertEquals(listOf<Double?>(0.0), operations.timeouts)
        assertEquals("out", text(result))
    }

    @Test
    fun `timeout failures from operations render pi's message with partial output`() = runTest {
        val operations = FakeBashOperations(output = "partial".toByteArray())
        operations.failure = OperationsException("timeout:7")
        val error = assertFailsWith<IllegalStateException> {
            bashTool(operations).execute("call-1", arguments("ls", timeout = 7.0)) {}
        }
        assertEquals("partial\n\nCommand timed out after 7 seconds", error.message)
    }

    @Test
    fun `aborted failures from operations render pi's message`() = runTest {
        val operations = FakeBashOperations(output = "partial".toByteArray())
        operations.failure = OperationsException("aborted")
        val error = assertFailsWith<IllegalStateException> {
            bashTool(operations).execute("call-1", arguments("ls")) {}
        }
        assertEquals("partial\n\nCommand aborted", error.message)
    }

    @Test
    fun `nonzero exit renders pi's message`() = runTest {
        val operations = FakeBashOperations(exitCode = 3)
        val error = assertFailsWith<IllegalStateException> {
            bashTool(operations).execute("call-1", arguments("ls")) {}
        }
        assertEquals("out\n\nCommand exited with code 3", error.message)
    }

    @Test
    fun `missing exit code renders pi's message`() = runTest {
        val operations = FakeBashOperations(exitCode = null)
        val error = assertFailsWith<IllegalStateException> {
            bashTool(operations).execute("call-1", arguments("ls")) {}
        }
        assertEquals("out\n\nCommand terminated without an exit code", error.message)
    }

    @Test
    fun `command argument is required at execution`() = runTest {
        val operations = FakeBashOperations()
        val error = assertFailsWith<IllegalArgumentException> {
            bashTool(operations).execute(
                "call-1",
                buildJsonObject { put("timeout", 1.0) }
            ) {}
        }
        assertEquals("bash: missing required argument 'command'", error.message)
        assertTrue(operations.commands.isEmpty())
    }
}
