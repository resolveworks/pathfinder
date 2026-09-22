package works.resolve.pathfinder.ssh

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import works.resolve.pathfinder.codingagent.core.tools.OperationsException

class RemoteBashOperationsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `timeout validation rejects non-positive and non-finite values with pi's messages`() =
        runTest {
            for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val error = assertFailsWith<IllegalStateException> {
                    withBashTimeout(invalid) { }
                }
                assertEquals(
                    "Invalid timeout: must be a finite number of seconds",
                    error.message
                )
            }
        }

    @Test
    fun `timeout validation rejects oversized values with pi's message`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            withBashTimeout(2_147_484.0) { }
        }
        assertEquals("Invalid timeout: maximum is 2147483.647 seconds", error.message)
    }

    @Test
    fun `expired timeout fails with pi's timeout seconds message`() = runTest {
        val error = assertFailsWith<OperationsException> {
            withBashTimeout(1.0) { delay(10_000) }
        }
        assertEquals("timeout:1", error.message)
        val fractional = assertFailsWith<OperationsException> {
            withBashTimeout(0.05) { delay(10_000) }
        }
        assertEquals("timeout:0.05", fractional.message)
    }

    @Test
    fun `null timeout imposes no deadline`() = runTest {
        var ran = false
        val result = withBashTimeout(null) {
            delay(5_000)
            ran = true
            "done"
        }
        assertEquals("done", result)
        assertTrue(ran)
    }

    @Test
    fun `cancellation is not a timeout and propagates unchanged`() = runTest {
        var caught: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withBashTimeout(60_000.0) { awaitCancellation() }
            } catch (e: CancellationException) {
                caught = e
            }
        }
        job.cancelAndJoin()
        assertTrue(caught is CancellationException && caught !is TimeoutCancellationException)
    }

    @Test
    fun `exec validates the timeout before dialing`() = runTest {
        val operations = RemoteBashOperations(emptyProvider())
        val error = assertFailsWith<IllegalStateException> {
            operations.exec("ls", "/", {}, 0.0)
        }
        assertEquals("Invalid timeout: must be a finite number of seconds", error.message)
    }

    @Test
    fun `exec without a timeout still dials, surfacing connection failures`() = runTest {
        val operations = RemoteBashOperations(emptyProvider())
        val error = assertFailsWith<OperationsException> {
            operations.exec("ls", "/", {}, null)
        }
        assertEquals("Machine unavailable: no machine configured", error.message)
    }

    private fun emptyProvider(): SshConnectionProvider {
        val machineStore = MachineStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob()),
                produceFile = {
                    File(tmpFolder.root, "machines_${System.nanoTime()}.preferences_pb")
                }
            ),
            MachineKeyStore(File(tmpFolder.root, "machine-keys"), { it }, { it })
        )
        return SshConnectionProvider(
            machineStore,
            SshConnectionHelper(machineStore),
            TofuHostKeyConfirmer(),
            flowOf(null)
        )
    }
}
