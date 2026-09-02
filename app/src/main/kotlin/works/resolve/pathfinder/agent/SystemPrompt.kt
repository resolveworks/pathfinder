package works.resolve.pathfinder.agent

/**
 * Ports `AgentSession._normalizePromptSnippet`
 * (packages/coding-agent/src/core/agent-session.ts:1042-1049): maps
 * null/empty/whitespace-only snippets to null and collapses newline and
 * whitespace runs to a single trimmed line.
 */
private fun normalizePromptSnippet(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val oneLine = text.replace("[\\r\\n]+".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    return oneLine.ifEmpty { null }
}

/**
 * Builds the tool-dependent sections of the default system prompt. Ports the
 * tool-section composition of pi's `buildSystemPrompt`
 * (packages/coding-agent/src/core/system-prompt.ts:28) fed from
 * `AgentSession._rebuildSystemPrompt`
 * (packages/coding-agent/src/core/agent-session.ts:1066).
 *
 * Divergences from upstream, kept as narrow as possible:
 * - pi's `buildSystemPrompt` additionally emits the coding-agent persona
 *   header, the current working directory, pi-docs paths, project context
 *   files, and skills — coding-agent app-layer text for which pathfinder has
 *   no surface. This port composes only the two tool-dependent sections
 *   (Available tools, Guidelines) with the upstream section layout.
 * - pi always sends a default persona prompt. Pathfinder today sends no
 *   system prompt at all for a no-tools chat, so this function returns null
 *   when [activeTools] is empty, preserving current behavior.
 */
fun buildSystemPrompt(activeTools: List<AgentTool>): String? {
    if (activeTools.isEmpty()) {
        return null
    }

    // A tool appears in Available tools only when it provides a snippet.
    // This composed function replaces both pi's collection step and its
    // rendering gate: `AgentSession._normalizePromptSnippet`
    // (packages/coding-agent/src/core/agent-session.ts:1042-1049) maps
    // null/empty/whitespace-only snippets to absent and collapses newline
    // and whitespace runs to a single trimmed line, and `buildSystemPrompt`
    // then keeps a tool only when `!!toolSnippets?.[name]` (JS truthiness —
    // an empty string is falsy there too).
    val visibleTools = activeTools.mapNotNull { tool ->
        normalizePromptSnippet(tool.promptSnippet)?.let { tool to it }
    }
    val toolsList =
        if (visibleTools.isNotEmpty()) {
            visibleTools.joinToString("\n") { "- ${it.first.definition.name}: ${it.second}" }
        } else {
            "(none)"
        }

    // Set-deduped, insertion-ordered guidelines accumulator (pi's
    // `guidelinesList` + `guidelinesSet`); per-tool guidelines first in tool
    // order (pi trims each and skips empty strings), then the always-on pair.
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
