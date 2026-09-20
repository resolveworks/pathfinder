package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.utils.getSystemMessageText

/**
 * Ordered system prompt sections, keyed by name. `preamble` is untagged
 * text; every other section is wrapped in a tag of the same name so the
 * model can match later updates to it. These become `SystemMessage.sections`
 * in the transcript.
 */
typealias SystemPromptSections = Map<String, String>

// pi's static persona header; the harness name is pathfinder's.
private const val PREAMBLE =
    "You are an expert coding assistant operating inside pathfinder, a coding agent harness. " +
        "You help users by reading files, executing commands, editing code, and writing new files."

/** Normalizes a tool's prompt snippet: blank input becomes null, otherwise a single trimmed line. */
private fun normalizePromptSnippet(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val oneLine = text.replace("[\\r\\n]+".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    return oneLine.ifEmpty { null }
}

/**
 * pi's buildRules: set-deduped on the trimmed rule, insertion-ordered —
 * pi's file-exploration rule first (only the bash branch can fire here:
 * grep/find/ls/powershell are unported, and no tool of those names reaches
 * this prompt), then per-tool guidelines in tool order, then the
 * always-on pair.
 */
private fun buildRules(activeTools: List<AgentTool>): String {
    val rules = mutableListOf<String>()
    val seen = HashSet<String>()
    fun addRule(rule: String) {
        val normalized = rule.trim()
        if (normalized.isEmpty() || !seen.add(normalized)) return
        rules.add(normalized)
    }

    val toolNames = activeTools.map { it.definition.name }.toSet()
    if ("bash" in toolNames) {
        addRule("Use bash for file operations like ls, rg, find")
    }

    for (tool in activeTools) {
        for (guideline in tool.promptGuidelines) {
            addRule(guideline)
        }
    }
    addRule("Be concise in your responses")
    addRule("Show file paths clearly when working with files")
    return rules.joinToString("\n") { "- $it" }
}

/**
 * Build the ordered, independently replaceable sections of the structured
 * system prompt: `preamble`, `tools`, `rules`, and `cwd` when there is one.
 *
 * Divergences from pi (reduced producers):
 * - Pi also emits `docs` (pi-docs paths), `addendum` (appendSystemPrompt),
 *   `project_context`, `skills`, `customPrompt` (replacing the preamble),
 *   forced prompts, and extension-contributed sections; none has a
 *   pathfinder producer. The custom-tools remark under `tools` is likewise
 *   an unported pi-only concern, as are pi's option-normalization and
 *   prompt-state layers (`normalizeBuildSystemPromptOptions`,
 *   `buildSystemPromptState`), which exist to hand extensions a mutable
 *   options object and to carry forced prompts.
 * - The `cwd` section is emitted only when [cwd] is non-empty: a session
 *   with no working directory (e.g. web tools only) omits the section
 *   rather than shipping pi's always-present tag with an empty value —
 *   Android has no working directory of its own, and the app layer passes
 *   the SSH remote cwd (with pi ssh-example annotation) when there is one.
 * - Like pi, a persona prompt is always sent — an empty tool set yields
 *   the preamble with "(none)" in `tools`, never an absent prompt.
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
fun buildSystemPromptSections(
    activeTools: List<AgentTool>,
    cwd: String = ""
): SystemPromptSections {
    // Inclusion rule: a tool appears in the tools section only when its
    // snippet normalizes to a non-null line (pi gates on
    // `!!toolSnippets[name]` — an empty string is falsy there too).
    val visibleTools = activeTools.mapNotNull { tool ->
        normalizePromptSnippet(tool.promptSnippet)?.let { tool.definition.name to it }
    }
    val toolsList =
        if (visibleTools.isNotEmpty()) {
            visibleTools.joinToString("\n") { (name, snippet) -> "- $name: $snippet" }
        } else {
            "(none)"
        }

    val promptSections = LinkedHashMap<String, String>()
    promptSections["preamble"] = PREAMBLE
    promptSections["tools"] = toolsList
    promptSections["rules"] = buildRules(activeTools)
    // pi normalizes Windows separators; harmless for remote POSIX paths.
    val promptCwd = cwd.replace("\\", "/")
    if (promptCwd.isNotEmpty()) promptSections["cwd"] = promptCwd

    val sections = LinkedHashMap<String, String>()
    for ((name, content) in promptSections) {
        sections[name] = if (name == "preamble") content else "<$name>\n$content\n</$name>"
    }
    return sections
}

/** Build the system prompt text, rendered exactly as the transcript's system message replays it. */
fun buildSystemPrompt(activeTools: List<AgentTool>, cwd: String = ""): String =
    getSystemMessageText(
        SystemMessage(content = emptyList(), sections = buildSystemPromptSections(activeTools, cwd))
    )

/**
 * Diff the sections the model currently has (replayed from the transcript,
 * so never null) against the desired ones. Returns a `SystemMessage.sections`
 * patch, or null when nothing changed.
 */
fun diffSystemPromptSections(
    previous: Map<String, String?>,
    current: SystemPromptSections
): Map<String, String?>? {
    val patch = LinkedHashMap<String, String?>()
    for ((name, text) in current) {
        if (previous[name] != text) patch[name] = text
    }
    for (name in previous.keys) {
        if (!current.containsKey(name)) patch[name] = null
    }
    return if (patch.isEmpty()) null else patch
}
