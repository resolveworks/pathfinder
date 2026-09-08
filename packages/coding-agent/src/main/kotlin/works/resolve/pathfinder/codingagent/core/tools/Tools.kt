package works.resolve.pathfinder.codingagent.core.tools

import works.resolve.pathfinder.agent.AgentTool

/**
 * Options for the ported coding tool set. Divergence from pi: every tool's
 * operations are required — pi's defaults construct local-filesystem
 * operations, which this platform-neutral module does not provide.
 */
class ToolsOptions(
    val read: ReadToolOptions,
    val bash: BashToolOptions,
    val edit: EditToolOptions,
    val write: WriteToolOptions
)

/** pi's default active coding tool set: read, bash, edit, write. */
fun createCodingTools(cwd: String, options: ToolsOptions): List<AgentTool> = listOf(
    createReadTool(cwd, options.read),
    createBashTool(cwd, options.bash),
    createEditTool(cwd, options.edit),
    createWriteTool(cwd, options.write)
)
