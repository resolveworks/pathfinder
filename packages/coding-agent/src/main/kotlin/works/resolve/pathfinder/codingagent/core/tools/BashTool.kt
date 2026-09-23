package works.resolve.pathfinder.codingagent.core.tools

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.StrictJsonSchemaMode
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.double
import works.resolve.pathfinder.ai.utils.str

class BashToolOptions(
    val operations: BashOperations,
    /** Where the full-output temp file is written (upstream uses `os.tmpdir()`;
     * the app passes `cacheDir`). */
    val tempDir: String
)

class BashToolInput(val command: String, val timeout: Double?)

/** Details for UI renderers: bash truncation and full-output temp file path. */
class BashToolDetails(val truncation: TruncationResult?, val fullOutputPath: String?) {
    fun toJson(includeTruncation: Boolean): JsonObject = buildJsonObject {
        if (includeTruncation && truncation != null) {
            put("truncation", truncation.toJson())
        }
        fullOutputPath?.let { put("fullOutputPath", it) }
    }
}

/**
 * Upstream keeps this constant in `renderers/bash.ts`; the renderers are
 * unported, so the shell's update throttle lives here.
 */
const val BASH_UPDATE_THROTTLE_MS = 100L

/**
 * The bash tool shell.
 *
 * Divergences from pi, all preserving the observable contract:
 * - [BashOperations] are required (no default local shell; `shell.ts` shell
 *   detection and the env/spawn-hook machinery are unported).
 * - pi's AbortSignal is coroutine cancellation. Upstream's local operations
 *   kill the process tree and the shell converts that into a rejection whose
 *   message carries the partial output; here cancellation is caught, the
 *   accumulated output snapshotted, and pi's exact "Command aborted" error
 *   thrown instead — a plain CancellationException cannot carry the
 *   partial-output payload. Operations may also fail with pi's
 *   "aborted"/"timeout:N" messages; those are detected identically to
 *   upstream.
 * - Like pi's shell, the raw `timeout` value is forwarded to the operations,
 * which own validation and enforcement (see [BashOperations.exec]).
 * - The session-environment feature (PI_* env, its guideline bullet) is
 *   unported: bash contributes its prompt snippet but no guidelines.
 */
class BashTool internal constructor(private val cwd: String, private val options: BashToolOptions) :
    AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description =
            "Execute a bash command in the current working directory. Returns stdout and " +
                "stderr. Output is truncated to last $DEFAULT_MAX_LINES lines or " +
                "${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). If truncated, full " +
                "output is saved to a temp file. Optionally provide a timeout in seconds.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "command",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to execute")
                        }
                    )
                    put(
                        "timeout",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Timeout in seconds (optional, no default timeout)")
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("command"))))
        },
        constrainedSampling = ConstrainedSamplingConfig.JsonSchema(StrictJsonSchemaMode.PREFER)
    )

    override val label: String = NAME

    override val promptSnippet: String = "Execute bash commands (ls, grep, find, etc.)"

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val command =
            arguments.str("command")
                ?: throw IllegalArgumentException("bash: missing required argument 'command'")
        val timeout = arguments.double("timeout")

        val output =
            OutputAccumulator(
                options.tempDir,
                OutputAccumulatorOptions(tempFilePrefix = TEMP_FILE_PREFIX)
            )

        return coroutineScope {
            // Throttled update state, shared between the exec callback (arbitrary
            // thread) and the timer coroutine; guarded by `lock`. The
            // System.currentTimeMillis() reads below are wall-time reads
            // sanctioned by the AGENTS.md "Time and deadlines" rule: timing
            // the output throttle is this code's job.
            val lock = Any()
            var acceptingOutput = true
            var updateTimer: kotlinx.coroutines.Job? = null
            var updateDirty = false
            var lastUpdateAt = 0L

            fun emitOutputUpdateLocked() {
                if (!updateDirty) {
                    return
                }
                updateDirty = false
                lastUpdateAt = System.currentTimeMillis()
                val snapshot = output.snapshot(persistIfTruncated = true)
                onUpdate(
                    AgentToolResult(
                        content = listOf(TextContent(snapshot.content)),
                        details = BashToolDetails(
                            truncation = snapshot.truncation.takeIf { it.truncated },
                            fullOutputPath = snapshot.fullOutputPath
                        ).toJson(includeTruncation = true)
                    )
                )
            }

            fun scheduleOutputUpdate() {
                synchronized(lock) {
                    updateDirty = true
                    val delayMs =
                        BASH_UPDATE_THROTTLE_MS - (System.currentTimeMillis() - lastUpdateAt)
                    if (delayMs <= 0) {
                        updateTimer?.cancel()
                        updateTimer = null
                        emitOutputUpdateLocked()
                        return
                    }
                    if (updateTimer == null) {
                        // LAZY start guarantees the field is assigned before the body runs.
                        val timer = launch(start = CoroutineStart.LAZY) {
                            delay(delayMs)
                            synchronized(lock) {
                                updateTimer = null
                                emitOutputUpdateLocked()
                            }
                        }
                        updateTimer = timer
                        timer.start()
                    }
                }
            }

            onUpdate(AgentToolResult(content = emptyList()))

            val handleData: (ByteArray) -> Unit = handler@{ data ->
                if (!synchronized(lock) { acceptingOutput }) {
                    return@handler
                }
                output.append(data)
                scheduleOutputUpdate()
            }

            fun finishOutput(): OutputSnapshot {
                synchronized(lock) {
                    acceptingOutput = false
                }
                output.finish()
                synchronized(lock) {
                    updateTimer?.cancel()
                    updateTimer = null
                    emitOutputUpdateLocked()
                }
                val snapshot = output.snapshot(persistIfTruncated = true)
                output.closeTempFile()
                return snapshot
            }

            fun formatOutput(
                snapshot: OutputSnapshot,
                emptyText: String = "(no output)"
            ): Pair<String, JsonObject?> {
                val truncation = snapshot.truncation
                var text = snapshot.content.ifEmpty { emptyText }
                var details: JsonObject? = null
                if (truncation.truncated) {
                    details =
                        BashToolDetails(
                            truncation,
                            snapshot.fullOutputPath
                        ).toJson(includeTruncation = true)
                    val startLine = truncation.totalLines - truncation.outputLines + 1
                    val endLine = truncation.totalLines
                    text += when {
                        truncation.lastLinePartial -> {
                            val lastLineSize = formatSize(output.getLastLineBytes())
                            "\n\n[Showing last ${formatSize(
                                truncation.outputBytes
                            )} of line $endLine " +
                                "(line is $lastLineSize). Full output: ${snapshot.fullOutputPath}]"
                        }

                        truncation.truncatedBy == "lines" ->
                            "\n\n[Showing lines $startLine-$endLine of ${truncation.totalLines}. " +
                                "Full output: ${snapshot.fullOutputPath}]"

                        else ->
                            "\n\n[Showing lines $startLine-$endLine of ${truncation.totalLines} " +
                                "(${formatSize(
                                    DEFAULT_MAX_BYTES
                                )} limit). Full output: ${snapshot.fullOutputPath}]"
                    }
                }
                return text to details
            }

            fun appendStatus(text: String, status: String): String =
                if (text.isEmpty()) status else "$text\n\n$status"

            try {
                val exitCode: Int?
                try {
                    exitCode = options.operations.exec(command, cwd, handleData, timeout)
                } catch (err: CancellationException) {
                    val (text) = formatOutput(finishOutput(), "")
                    throw IllegalStateException(appendStatus(text, "Command aborted"))
                } catch (err: Throwable) {
                    val (text) = formatOutput(finishOutput(), "")
                    val message = err.message
                    when {
                        message == "aborted" ->
                            throw IllegalStateException(appendStatus(text, "Command aborted"))

                        message != null && message.startsWith("timeout:") -> {
                            val timeoutSecs = message.split(":")[1]
                            throw IllegalStateException(
                                appendStatus(text, "Command timed out after $timeoutSecs seconds")
                            )
                        }

                        else -> throw err
                    }
                }

                val snapshot = finishOutput()
                val (outputText, details) = formatOutput(snapshot)
                if (exitCode == null) {
                    throw IllegalStateException(
                        appendStatus(outputText, "Command terminated without an exit code")
                    )
                }
                if (exitCode != 0) {
                    throw IllegalStateException(
                        appendStatus(outputText, "Command exited with code $exitCode")
                    )
                }
                AgentToolResult(content = listOf(TextContent(outputText)), details = details)
            } finally {
                synchronized(lock) {
                    updateTimer?.cancel()
                    updateTimer = null
                }
            }
        }
    }

    companion object {
        const val NAME = "bash"

        const val TEMP_FILE_PREFIX = "pi-bash"
    }
}

fun createBashTool(cwd: String, options: BashToolOptions): AgentTool = BashTool(cwd, options)
