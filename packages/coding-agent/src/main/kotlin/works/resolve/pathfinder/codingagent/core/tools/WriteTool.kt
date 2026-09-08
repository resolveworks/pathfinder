package works.resolve.pathfinder.codingagent.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.str

class WriteToolOptions(val operations: WriteOperations)

class WriteToolInput(val path: String, val content: String)

/**
 * The write tool shell. Divergences from pi: [WriteOperations] are required
 * (no default local filesystem), `ctx.cwd` is the constructor parameter, and
 * pi's explicit abort checks between awaits become coroutine cancellation:
 * suspension points throw it, `withFileMutationQueue` releases the lock via
 * its own `finally`, and the agent loop surfaces cancellation with the same
 * "Operation aborted" outcome pi's `throwIfAborted` produces.
 */
class WriteTool internal constructor(
    private val cwd: String,
    private val options: WriteToolOptions
) : AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description =
            "Write content to a file. Creates the file if it doesn't exist, overwrites if it " +
                "does. Automatically creates parent directories.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file to write (relative or absolute)")
                        }
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Content to write to the file")
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
        }
    )

    override val label: String = NAME

    override val promptSnippet: String = "Create or overwrite files"

    override val promptGuidelines: List<String> =
        listOf("Use write only for new files or complete rewrites.")

    override fun validateArguments(arguments: JsonObject): JsonObject {
        requireString(arguments, "path")
        requireString(arguments, "content")
        return arguments
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val path =
            arguments.str("path")
                ?: throw IllegalArgumentException("write: missing required argument 'path'")
        val content =
            arguments.str("content")
                ?: throw IllegalArgumentException("write: missing required argument 'content'")
        val ops = options.operations

        val absolutePath = resolveToCwd(path, cwd)
        val dir = posixDirname(absolutePath)
        return withFileMutationQueue(absolutePath) {
            // Create parent directories if needed.
            ops.mkdir(dir)

            // Write the file contents.
            ops.writeFile(absolutePath, content)

            AgentToolResult(content = listOf(TextContent("Successfully wrote to $path")))
        }
    }

    companion object {
        const val NAME = "write"
    }
}

fun createWriteTool(cwd: String, options: WriteToolOptions): AgentTool = WriteTool(cwd, options)
