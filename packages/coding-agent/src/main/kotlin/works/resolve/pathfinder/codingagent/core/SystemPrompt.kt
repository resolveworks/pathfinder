package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.AgentTool

/** Normalizes a tool's prompt snippet: blank input becomes null, otherwise a single trimmed line. */
private fun normalizePromptSnippet(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val oneLine = text.replace("[\\r\\n]+".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    return oneLine.ifEmpty { null }
}

/**
 * Builds pi's default system prompt layout (persona header, Available
 * tools, Guidelines) for the active tool set.
 *
 * Divergences from pi:
 * - The persona header names pathfinder instead of pi, and pi's
 *   coding-agent-only sections (pi-docs paths, project context files,
 *   skills, the custom-tools remark) are app-layer text with no pathfinder
 *   surface and are not emitted. The cwd trailer IS ported: an empty [cwd]
 *   (a session with no working directory, e.g. web tools only) omits it
 *   rather than writing pi's always-present line with an empty value —
 *   Android has no working directory of its own, and the app layer passes
 *   the SSH remote cwd (with pi ssh-example annotation) when there is one.
 * - Like pi, a persona prompt is always sent — an empty tool set yields
 *   the header with an empty tools list, never null.
 *
 * Note on pi's `packages/ai/src/session-resources.ts`: it is NOT a
 * system-prompt resources concept — it is a session-scoped cleanup registry
 * (`registerSessionResourceCleanup`/`cleanupSessionResources`) whose only
 * registration closes the Codex adapter's cached WebSockets on session
 * dispose. That omission is documented on the Codex adapter's own KDoc (its
 * connection pool owns cleanup via idle TTL / max connection age); nothing
 * here is un-ported. The system-prompt-adjacent "resources" pi does have —
 * skill and prompt-template invocation formatting (`harness/skills.ts`,
 * `harness/prompt-templates.ts`, tested by `resource-formatting.test.ts`;
 * loaded at HEAD by coding-agent's fs-based `resource-loader.ts`) — belong
 * to the unported harness skills / prompt-template surface, which this
 * file's first divergence bullet already covers. Not planned unless pi's
 * interactive path starts feeding loaded resources into the agent-level
 * system prompt.
 */
fun buildSystemPrompt(activeTools: List<AgentTool>, cwd: String = ""): String {
    // Inclusion rule: a tool appears in Available tools only when its
    // snippet normalizes to a non-null line (pi gates on
    // `!!toolSnippets?.[name]` — an empty string is falsy there too).
    val visibleTools = activeTools.mapNotNull { tool ->
        normalizePromptSnippet(tool.promptSnippet)?.let { tool to it }
    }
    val toolsList =
        if (visibleTools.isNotEmpty()) {
            visibleTools.joinToString("\n") { "- ${it.first.definition.name}: ${it.second}" }
        } else {
            "(none)"
        }

    // Set-deduped, insertion-ordered: pi's file-exploration guideline
    // first, then per-tool guidelines in tool order, then the always-on pair.
    val guidelinesList = mutableListOf<String>()
    val guidelinesSet = HashSet<String>()
    fun addGuideline(guideline: String) {
        if (guideline !in guidelinesSet) {
            guidelinesSet.add(guideline)
            guidelinesList.add(guideline)
        }
    }

    val toolNames = activeTools.map { it.definition.name }.toSet()

    // pi's file-exploration guideline: only the bash branch can fire here —
    // grep/find/ls/powershell are unported, and no tool of those names
    // reaches this prompt.
    if ("bash" in toolNames &&
        "grep" !in toolNames && "find" !in toolNames && "ls" !in toolNames &&
        "powershell" !in toolNames
    ) {
        addGuideline("Use bash for file operations like ls, rg, find")
    }

    for (tool in activeTools) {
        for (guideline in tool.promptGuidelines) {
            val normalized = guideline.trim()
            if (normalized.isNotEmpty()) {
                addGuideline(normalized)
            }
        }
    }

    // Always include these (pi: added last, after per-tool guidelines).
    addGuideline("Be concise in your responses")
    addGuideline("Show file paths clearly when working with files")

    val guidelines = guidelinesList.joinToString("\n") { "- $it" }

    // pi's static persona header; the harness name is pathfinder's.
    val persona =
        "You are an expert coding assistant operating inside pathfinder, a coding agent harness. " +
            "You help users by reading files, executing commands, editing code, and writing new files."
    // pi normalizes Windows separators; harmless for remote POSIX paths.
    val promptCwd = cwd.replace("\\", "/")
    return "$persona\n\n" +
        "Available tools:\n$toolsList\n\nGuidelines:\n$guidelines" +
        if (promptCwd.isNotEmpty()) "\nCurrent working directory: $promptCwd" else ""
}
