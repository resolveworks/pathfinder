package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.AgentTool

/** Normalizes a tool's prompt snippet: blank input becomes null, otherwise a single trimmed line. */
private fun normalizePromptSnippet(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val oneLine = text.replace("[\\r\\n]+".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    return oneLine.ifEmpty { null }
}

/**
 * Builds the tool-dependent sections of pi's default system prompt (Available
 * tools, Guidelines) with the upstream section layout.
 *
 * Divergences from pi:
 * - pi's `buildSystemPrompt` additionally emits the coding-agent persona
 *   header, cwd, pi-docs paths, project context files, and skills —
 *   coding-agent app-layer text for which pathfinder has no surface.
 * - pi always sends a default persona prompt; pathfinder sends no system
 *   prompt for a no-tools chat, so this returns null when [activeTools] is
 *   empty.
 *
 * Verified at pin b8b873b98 (differences.md §5.1): upstream's
 * `packages/ai/src/session-resources.ts` is NOT a system-prompt resources
 * concept — it is a session-scoped cleanup registry
 * (`registerSessionResourceCleanup`/`cleanupSessionResources`) whose only
 * registration closes the Codex adapter's cached WebSockets on session
 * dispose. That omission is documented on the Codex adapter's own KDoc
 * (its connection pool owns cleanup via idle TTL / max connection age);
 * nothing here is un-ported. The system-prompt-adjacent "resources" pi
 * does have — skill and prompt-template invocation formatting
 * (`harness/skills.ts`, `harness/prompt-templates.ts`, tested by
 * `resource-formatting.test.ts`; loaded at HEAD by coding-agent's
 * fs-based `resource-loader.ts`) — belong to the unported harness skills /
 * prompt-template surface, which this file's first divergence bullet already
 * covers. Not planned unless pi's interactive path starts feeding loaded
 * resources into the agent-level system prompt.
 */
fun buildSystemPrompt(activeTools: List<AgentTool>): String? {
    if (activeTools.isEmpty()) {
        return null
    }

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

    // Set-deduped, insertion-ordered: per-tool guidelines first in tool
    // order, then the always-on pair.
    val guidelinesList = mutableListOf<String>()
    val guidelinesSet = HashSet<String>()
    fun addGuideline(guideline: String) {
        if (guideline !in guidelinesSet) {
            guidelinesSet.add(guideline)
            guidelinesList.add(guideline)
        }
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

    return "Available tools:\n$toolsList\n\nGuidelines:\n$guidelines"
}
