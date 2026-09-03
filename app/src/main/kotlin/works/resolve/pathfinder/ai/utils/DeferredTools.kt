package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage

private val identityToolName: (String) -> String = { it }

data class DeferredToolPlacement(val immediate: List<Tool>, val deferred: Map<String, Tool>)

/** Split current tools into prefix and transcript-loaded definitions. */
fun splitDeferredTools(
    context: Context,
    enabled: Boolean,
    normalizeName: (String) -> String = identityToolName,
): DeferredToolPlacement {
    val uniqueTools = LinkedHashMap<String, Tool>()
    for (tool in context.tools) uniqueTools[normalizeName(tool.name)] = tool
    if (!enabled) return DeferredToolPlacement(uniqueTools.values.toList(), emptyMap())

    val deferredNames = mutableSetOf<String>()
    val usedNames = mutableSetOf<String>()
    for (message in context.messages) {
        when (message) {
            is AssistantMessage ->
                message.content.filterIsInstance<ToolCall>()
                    .forEach { usedNames.add(normalizeName(it.name)) }
            is ToolResultMessage ->
                for (name in message.addedToolNames) {
                    val normalizedName = normalizeName(name)
                    if (normalizedName !in usedNames) deferredNames.add(normalizedName)
                }
            else -> {}
        }
    }

    val immediate = mutableListOf<Tool>()
    val deferred = LinkedHashMap<String, Tool>()
    for ((name, tool) in uniqueTools) {
        if (name in deferredNames) deferred[name] = tool else immediate.add(tool)
    }
    return DeferredToolPlacement(immediate, deferred)
}
