package works.resolve.pathfinder.codingagent.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.str

class EditToolOptions(val operations: EditOperations)

/** Details for UI renderers: display diff, unified patch, first changed line. */
class EditToolDetails(val diff: String, val patch: String, val firstChangedLine: Int?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("diff", diff)
        put("patch", patch)
        firstChangedLine?.let { put("firstChangedLine", it) }
    }
}

private fun isSingleEditInput(value: Any?): Boolean {
    if (value !is JsonObject) {
        return false
    }
    val oldText = value["oldText"]
    val newText = value["newText"]
    return oldText is JsonPrimitive && oldText.isString && newText is JsonPrimitive &&
        newText.isString
}

/**
 * pi's `prepareArguments` compatibility shims, run during validation:
 * - some models send `edits` as a JSON string instead of an array
 * - some send a single edit object instead of a one-element array
 * - legacy calls carry top-level `oldText`/`newText` instead of `edits`
 */
private fun prepareEditArguments(input: JsonObject): JsonObject {
    val args = input.toMutableMap()

    val edits = args["edits"]
    if (edits is JsonPrimitive && edits.isString) {
        try {
            val parsed = lenientJson.parseToJsonElement(edits.content)
            when {
                parsed is JsonArray -> args["edits"] = parsed
                isSingleEditInput(parsed) -> args["edits"] = JsonArray(listOf(parsed))
                else -> {}
            }
        } catch (_: Throwable) {
        }
    } else if (edits != null && isSingleEditInput(edits)) {
        args["edits"] = JsonArray(listOf(edits))
    }

    val legacyOldText = (args["oldText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val legacyNewText = (args["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (legacyOldText == null || legacyNewText == null) {
        return JsonObject(args)
    }

    val editsList: List<JsonElement> = (args["edits"] as? JsonArray)?.toList() ?: emptyList()
    val legacyEdit = buildJsonObject {
        put("oldText", legacyOldText)
        put("newText", legacyNewText)
    }
    val rest =
        args.filterKeys { it != "oldText" && it != "newText" } +
            ("edits" to JsonArray(editsList + legacyEdit))
    return JsonObject(rest)
}

private fun validateEditInput(input: JsonObject): List<Edit> {
    val editsArray = input["edits"] as? JsonArray
    if (editsArray == null || editsArray.isEmpty()) {
        throw IllegalStateException(
            "Edit tool input is invalid. edits must contain at least one replacement."
        )
    }
    return editsArray.mapIndexed { index, element ->
        val edit = element as? JsonObject
            ?: throw IllegalArgumentException(
                "edits[$index] must be an object with oldText and newText strings"
            )
        val oldText = edit.str("oldText")
        val newText = edit.str("newText")
        if (oldText == null || newText == null) {
            throw IllegalArgumentException(
                "edits[$index] must be an object with oldText and newText strings"
            )
        }
        editOf(oldText, newText)
    }
}

/**
 * The edit tool shell. Divergences from pi: [EditOperations] are required (no
 * default local filesystem), `ctx.cwd` is the constructor parameter, pi's
 * AbortSignal is coroutine cancellation (see [WriteTool]), and the
 * "Error code:" branch of the access-failure message reads
 * [OperationsException.code] since arbitrary Kotlin throwables carry no
 * Node-style `code` property.
 */
class EditTool internal constructor(private val cwd: String, private val options: EditToolOptions) :
    AgentTool {

    override val definition: Tool = Tool(
        name = NAME,
        description =
            "Edit a single file using exact text replacement. Every edits[].oldText must " +
                "match a unique, non-overlapping region of the original file. If two changes " +
                "affect the same block or nearby lines, merge them into one edit instead of " +
                "emitting overlapping edits. Do not include large unchanged regions just to " +
                "connect distant changes.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Path to the file to edit (relative or absolute)")
                        }
                    )
                    put(
                        "edits",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "description",
                                "One or more targeted replacements. Each edit is matched against " +
                                    "the original file, not incrementally. Do not include " +
                                    "overlapping or nested edits. If two changes touch the same " +
                                    "block or nearby lines, merge them into one edit instead."
                            )
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("edits"))))
        }
    )

    override val label: String = NAME

    override val promptSnippet: String =
        "Make precise file edits with exact text replacement, " +
            "including multiple disjoint edits in one call"

    override val promptGuidelines: List<String> = listOf(
        "Use edit for precise changes (edits[].oldText must match exactly)",
        "When changing multiple separate locations in one file, use one edit call with " +
            "multiple entries in edits[] instead of multiple edit calls",
        "Each edits[].oldText is matched against the original file, not after earlier " +
            "edits are applied. Do not emit overlapping or nested edits. Merge nearby " +
            "changes into one edit.",
        "Keep edits[].oldText as small as possible while still being unique in the file. " +
            "Do not pad with large unchanged regions."
    )

    override fun validateArguments(arguments: JsonObject): JsonObject {
        val prepared = prepareEditArguments(arguments)
        requireString(prepared, "path")
        val edits = prepared["edits"]
        if (edits == null) {
            throw IllegalArgumentException("missing required argument 'edits'")
        }
        if (edits !is JsonArray) {
            throw IllegalArgumentException("'edits' must be an array")
        }
        validateEditInput(prepared)
        return prepared
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val edits = validateEditInput(arguments)
        val path =
            arguments.str("path")
                ?: throw IllegalArgumentException("edit: missing required argument 'path'")
        val ops = options.operations
        val absolutePath = resolveToCwd(path, cwd)

        return withFileMutationQueue(absolutePath) {
            // Check if file exists.
            try {
                ops.access(absolutePath)
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Could not edit file: $path. ${operationsErrorMessage(error)}."
                )
            }

            // Read the file.
            val rawContent = ops.readFile(absolutePath)

            // Strip BOM before matching. The model will not include an invisible BOM in oldText.
            val (bom, content) = splitBom(rawContent)
            val originalEnding = detectLineEnding(content)
            val normalizedContent = normalizeToLF(content)
            val (baseContent, newContent) = applyEditsToNormalizedContent(
                normalizedContent,
                edits,
                path
            )

            val finalContent = bom + restoreLineEndings(newContent, originalEnding)
            ops.writeFile(absolutePath, finalContent)

            val diffResult = generateDiffString(baseContent, newContent)
            val patch = generateUnifiedPatch(path, baseContent, newContent)
            AgentToolResult(
                content = listOf(
                    TextContent("Successfully replaced ${edits.size} block(s) in $path.")
                ),
                details = EditToolDetails(
                    diffResult.diff,
                    patch,
                    diffResult.firstChangedLine
                ).toJson()
            )
        }
    }

    companion object {
        const val NAME = "edit"
    }
}

fun createEditTool(cwd: String, options: EditToolOptions): AgentTool = EditTool(cwd, options)
