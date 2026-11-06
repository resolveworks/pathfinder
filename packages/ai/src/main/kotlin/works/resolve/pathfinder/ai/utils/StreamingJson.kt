package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One step of a [JsonChange] path: an object member key or an array index. */
sealed interface JsonPathSegment {
    data class Key(val key: String) : JsonPathSegment

    data class Index(val index: Int) : JsonPathSegment
}

/** A path from the root value: member keys and array indices. */
typealias JsonPath = List<JsonPathSegment>

/** One change a piece of text made to the value; see [StreamingJsonParser]. */
sealed interface JsonChange {
    val path: JsonPath

    data class Set(override val path: JsonPath, val value: JsonElement) : JsonChange

    data class Append(override val path: JsonPath, val text: String) : JsonChange
}

/** Where a value sits in its parent; absent for a value in an invalid position, which is read but dropped. */
private sealed interface Slot {
    data object Root : Slot

    data class Member(val key: String) : Slot

    data class Element(val index: Int) : Slot
}

private sealed class Container {
    abstract val size: Int

    /** A fresh plain value, with the two open-path overrides applied to this container. */
    abstract fun copy(replacements: List<Pair<Slot, JsonElement>>): JsonElement

    class Members : Container() {
        val members = LinkedHashMap<String, JsonElement>()

        override val size: Int get() = members.size

        override fun copy(replacements: List<Pair<Slot, JsonElement>>): JsonElement {
            val copy = LinkedHashMap(members)
            for ((slot, value) in replacements) {
                if (slot is Slot.Member) copy[slot.key] = value
            }
            return JsonObject(copy)
        }
    }

    class Elements : Container() {
        val elements = ArrayList<JsonElement>()

        override val size: Int get() = elements.size

        override fun copy(replacements: List<Pair<Slot, JsonElement>>): JsonElement {
            val copy = ArrayList(elements)
            for ((slot, value) in replacements) {
                if (slot !is Slot.Element) continue
                if (slot.index < copy.size) copy[slot.index] = value else copy.add(value)
            }
            return JsonArray(copy)
        }
    }
}

private enum class FramePhase { KEY, COLON, VALUE, COMMA }

private enum class StringTarget { KEY, VALUE }

/** An open object or array. Its [container] is never handed out while open; snapshots get copies. */
private class Frame(
    val container: Container,
    val slot: Slot?,
    /** Where the container sits, when its changes are reported. */
    val path: JsonPath?,
    /** Whether changes inside it are reported; when not, its whole value is reported as it closes. */
    val reportsInside: Boolean
) {
    /** Objects: the key read last, where the member value goes. */
    var key: String? = null

    var phase: FramePhase =
        if (container is Container.Elements) FramePhase.VALUE else FramePhase.KEY
}

/** An unterminated string. */
private class OpenString(
    val target: StringTarget,
    val slot: Slot?,
    /** Where a value string sits, when its changes are reported. */
    val path: JsonPath?,
    /** The change this string extends, while it is the newest change of the piece. */
    var change: OpenChange? = null
) {
    val text = StringBuilder()

    /** Escape read so far: "" for none, otherwise a backslash and up to five more characters. */
    var escape = ""
}

/** A change still being extended by the piece being read. */
private sealed class OpenChange {
    abstract val path: JsonPath

    abstract fun toJsonChange(): JsonChange

    class Set(override val path: JsonPath, var value: JsonElement) : OpenChange() {
        override fun toJsonChange(): JsonChange = JsonChange.Set(path, value)
    }

    class Append(override val path: JsonPath, var text: String) : OpenChange() {
        override fun toJsonChange(): JsonChange = JsonChange.Append(path, text)
    }
}

private class Pending(val slot: Slot, val value: JsonElement)

// Changes are reported at most this deep, so a path costs O(1) to build.
private const val MAX_REPORTED_DEPTH = 32

// Copy credit per append and per appended character. The floor keeps small documents fresh at every snapshot.
private const val CREDIT_PER_APPEND = 256
private const val CREDIT_PER_CHARACTER = 8

// An unterminated number longer than this is not shown until it ends, so `snapshot()` stays cheap.
private const val MAX_PENDING_SCALAR_LENGTH = 64

private val NUMBER = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
private val NUMBER_WITHOUT_EXPONENT_DIGITS = Regex("^(-?(?:0|[1-9]\\d*)(?:\\.\\d+)?)[eE][+-]?$")
private val LITERALS: List<Pair<String, JsonElement>> = listOf(
    "true" to JsonPrimitive(true),
    "false" to JsonPrimitive(false),
    "null" to JsonNull
)

private val SIMPLE_ESCAPES: Map<Char, Char> = mapOf(
    '"' to '"',
    '\\' to '\\',
    '/' to '/',
    'b' to '\b',
    'f' to '\u000C',
    'n' to '\n',
    'r' to '\r',
    't' to '\t'
)

/**
 * Incremental, tolerant parsing of JSON text that arrives in pieces, such as streamed tool-call arguments.
 *
 * [StreamingJsonParser.append] reads each piece once, so a stream costs time and memory linear in its length, and
 * [StreamingJsonParser.snapshot] returns the value of the text read so far. Snapshots are plain JSON values and never
 * change once returned: the parser copies the containers that are still open (the "open path") into every new snapshot
 * and shares completed containers and strings. The rules for incomplete text follow [parseStreamingJson]:
 *
 * - An unterminated string shows the characters read so far; an unterminated escape is held back until complete.
 * - An unterminated container shows the members and elements read so far.
 * - A member whose key or value has not started is omitted.
 * - An unterminated literal shows its value once it is a prefix of `true`, `false` or `null`; an unterminated number
 *   shows its value when the digits read so far form a number (an exponent without digits is ignored).
 *
 * Malformed text is read leniently, the way [repairJson] and [parseStreamingJson] treat it: raw control characters in
 * strings are kept, an invalid escape such as `\d` stays literal text, missing commas and colons are tolerated, values
 * in invalid positions and unknown words are dropped, and text after the root value is ignored. The parser never
 * throws. Callers that need the exact value of the finished text parse it once, e.g. with [parseToolCallArguments].
 *
 * Differences from [parseStreamingJson] on incomplete text: trailing whitespace inside an unterminated string is kept
 * (it was trimmed), a string that contains a raw control character or an invalid escape stays visible while it streams
 * (it was dropped until the document was complete), and `NaN` and `Infinity` words are dropped.
 *
 * [StreamingJsonParser.append] also returns the changes the piece made, for a writer that keeps its own copy of the
 * value and applies them instead of re-assigning snapshots: [JsonChange.Set] when a key, an array item or a finished
 * value appears (a new string is set once with its first text, a container empty), [JsonChange.Append] when the piece
 * extended an open string. Unterminated numbers and literals are not reported until they end. A container nested more
 * than 32 levels deep is reported as one `Set` of its whole value when it closes, so paths stay short.
 *
 * Cost: copying the open path costs its member and element count. Each [StreamingJsonParser.append] earns copy credit
 * proportional to its length plus a constant; [StreamingJsonParser.snapshot] spends it and returns the previous
 * snapshot while the open path costs more than the credit. So a document that keeps a very large container open
 * (thousands of elements) shows a preview that lags a little behind the text, and no input makes the total work grow
 * faster than the text.
 */
class StreamingJsonParser {
    private var root: JsonElement? = null
    private var done = false
    private val stack = ArrayList<Frame>()
    private var openString: OpenString? = null
    private var scalar = ""
    private var scalarSlot: Slot? = null

    /** Sum of `size + 1` over the open path: what a fresh snapshot copies. */
    private var pathCost = 0
    private var credit = 0
    private var dirty = false
    private var snapshot: JsonElement? = null

    /** Changes of the piece being read. */
    private val changes = ArrayList<OpenChange>()

    /**
     * Read the next piece of text; returns the changes it made, in order. Costs time linear in [text]'s length.
     */
    fun append(text: String): List<JsonChange> {
        credit += CREDIT_PER_APPEND + CREDIT_PER_CHARACTER * text.length
        changes.clear()
        openString?.change = null
        var index = 0
        while (index < text.length && !done) {
            val open = openString
            index = when {
                open != null -> readString(open, text, index)

                scalar.isNotEmpty() -> readScalar(text, index)

                else -> {
                    structural(text[index])
                    index + 1
                }
            }
        }
        return changes.map { it.toJsonChange() }
    }

    /**
     * The value of the text read so far: `null` before a value starts, otherwise a plain JSON value that never changes
     * afterwards. Returns the previous snapshot while the text read since did not change the value, or while a fresh
     * copy of the open path is not yet paid for (see the module comment).
     */
    fun snapshot(): JsonElement? {
        if (!dirty) return snapshot
        val pending = pendingValue()
        val cost = pathCost + if (pending == null) 0 else 1
        if (cost > credit) return snapshot
        credit -= cost
        dirty = false
        snapshot = copyOpenPath(pending)
        return snapshot
    }

    /** Read string characters from [start] until the string ends or the text does; returns the next index. */
    private fun readString(open: OpenString, text: String, start: Int): Int {
        var runStart = start
        var index = start
        while (index < text.length) {
            val code = text[index]
            if (open.escape.isNotEmpty()) {
                if (continueEscape(open, text[index])) {
                    runStart = index + 1
                    index++
                    continue
                }
                // Not part of the escape: read it as a normal string character below.
                runStart = index
            }
            if (code == '"') {
                if (index > runStart) appendString(open, text.substring(runStart, index))
                endString(open)
                return index + 1
            }
            if (code == '\\') {
                if (index > runStart) appendString(open, text.substring(runStart, index))
                open.escape = "\\"
                runStart = index + 1
            }
            index++
        }
        if (text.length > runStart) appendString(open, text.substring(runStart))
        return text.length
    }

    /** Read number or literal characters from [start] up to the next delimiter; returns the delimiter's index. */
    private fun readScalar(text: String, start: Int): Int {
        var end = start
        while (end < text.length && !isDelimiter(text[end])) end++
        if (end > start) {
            scalar += text.substring(start, end)
            if (scalarSlot != null) dirty = true
        }
        if (end < text.length) endScalar()
        return end
    }

    /** Handle one character outside strings. */
    private fun structural(char: Char) {
        when (char) {
            ' ', '\t', '\n', '\r' -> return

            '"' -> startString()

            '{' -> openContainer(Container.Members())

            '[' -> openContainer(Container.Elements())

            '}', ']' -> closeContainer()

            ':' -> {
                val frame = stack.lastOrNull() ?: return
                if (frame.container is Container.Members &&
                    frame.phase == FramePhase.COLON
                ) {
                    frame.phase = FramePhase.VALUE
                }
            }

            ',' -> {
                val frame = stack.lastOrNull() ?: return
                frame.phase =
                    if (frame.container is Container.Elements) FramePhase.VALUE else FramePhase.KEY
            }

            else -> {
                scalar = char.toString()
                scalarSlot = valueSlot()
                if (scalarSlot != null) dirty = true
            }
        }
    }

    /** Where a value starting now goes, or `null` when this position takes no value. */
    private fun valueSlot(): Slot? {
        val frame = stack.lastOrNull() ?: return if (root == null) Slot.Root else null
        if (frame.container is Container.Elements) return Slot.Element(frame.container.size)
        // A missing colon is tolerated: `{"a" 1}` reads as `{"a": 1}`.
        val key = frame.key
        if ((frame.phase == FramePhase.VALUE || frame.phase == FramePhase.COLON) &&
            key != null
        ) {
            return Slot.Member(key)
        }
        return null
    }

    private fun startString() {
        val frame = stack.lastOrNull()
        // A missing comma is tolerated: `{"a": 1 "b": 2}` reads the second member.
        if (
            frame != null &&
            frame.container is Container.Members &&
            (frame.phase == FramePhase.KEY || frame.phase == FramePhase.COMMA)
        ) {
            openString = OpenString(StringTarget.KEY, slot = null, path = null)
            return
        }
        val slot = valueSlot()
        val path = reportedPath(slot)
        val open = OpenString(StringTarget.VALUE, slot, path)
        if (slot != null) dirty = true
        if (path != null) open.change = report(OpenChange.Set(path, JsonPrimitive("")))
        openString = open
    }

    /** The path of a value at [slot] in the innermost open container, when its changes are reported. */
    private fun reportedPath(slot: Slot?): JsonPath? = when (slot) {
        null -> null

        is Slot.Root -> emptyList()

        is Slot.Member -> {
            val frame = stack.last()
            if (frame.reportsInside) frame.path!! + JsonPathSegment.Key(slot.key) else null
        }

        is Slot.Element -> {
            val frame = stack.last()
            if (frame.reportsInside) frame.path!! + JsonPathSegment.Index(slot.index) else null
        }
    }

    private fun report(change: OpenChange): OpenChange {
        changes.add(change)
        return change
    }

    /** Continue an escape with [char]; returns false when [char] is not part of it and must be read normally. */
    private fun continueEscape(open: OpenString, char: Char): Boolean {
        if (open.escape == "\\") {
            val decoded = SIMPLE_ESCAPES[char]
            if (decoded != null) {
                open.escape = ""
                appendString(open, decoded.toString())
                return true
            }
            if (char == 'u') {
                open.escape = "\\u"
                return true
            }
            // Invalid escape: keep the backslash as text and read the character normally, as repairJson() does.
            open.escape = ""
            appendString(open, "\\")
            return false
        }
        if (isHexDigit(char)) {
            open.escape += char
            if (open.escape.length < 6) return true
            val decoded = open.escape.substring(2).toInt(16).toChar()
            open.escape = ""
            appendString(open, decoded.toString())
            return true
        }
        // `\u` without four hex digits: keep it as text and read the character normally, as repairJson() does.
        val literal = open.escape
        open.escape = ""
        appendString(open, literal)
        return false
    }

    private fun appendString(open: OpenString, text: String) {
        open.text.append(text)
        if (open.target != StringTarget.VALUE) return
        if (open.slot != null) dirty = true
        val path = open.path ?: return
        // Extend this string's newest change while nothing came after it; otherwise report an append.
        val change = open.change
        if (change != null && changes.lastOrNull() === change) {
            when (change) {
                is OpenChange.Set ->
                    change.value =
                        JsonPrimitive((change.value as JsonPrimitive).content + text)

                is OpenChange.Append -> change.text += text
            }
            return
        }
        open.change = report(OpenChange.Append(path, text))
    }

    private fun endString(open: OpenString) {
        openString = null
        val frame = stack.lastOrNull()
        if (open.target == StringTarget.KEY) {
            if (frame == null) return
            frame.key = open.text.toString()
            frame.phase = FramePhase.COLON
            return
        }
        place(open.slot, JsonPrimitive(open.text.toString()))
        valueEnded(frame)
    }

    private fun endScalar() {
        val token = scalar
        val slot = scalarSlot
        scalar = ""
        scalarSlot = null
        val value = scalarValue(token)
        if (value != null) {
            place(slot, value)
            val path = reportedPath(slot)
            if (path != null) report(OpenChange.Set(path, value))
        }
        valueEnded(stack.lastOrNull())
    }

    /** The value of the unterminated scalar or value string at the end of the text, if it shows. */
    private fun pendingValue(): Pending? {
        val open = openString
        if (open != null) {
            if (open.target != StringTarget.VALUE) return null
            val slot = open.slot ?: return null
            return Pending(slot, JsonPrimitive(open.text.toString()))
        }
        val slot = scalarSlot ?: return null
        val token = scalar
        if (token.isEmpty() || token.length > MAX_PENDING_SCALAR_LENGTH) return null
        val value = pendingScalarValue(token) ?: return null
        return Pending(slot, value)
    }

    private fun openContainer(container: Container) {
        val slot = valueSlot()
        val path = reportedPath(slot)
        // A fresh, empty value: the working container keeps changing, and a change never does.
        place(
            slot,
            if (container is Container.Members) JsonObject(emptyMap()) else JsonArray(emptyList())
        )
        stack.add(Frame(container, slot, path, path != null && path.size < MAX_REPORTED_DEPTH))
        pathCost += 1
        dirty = true
        if (path != null) report(OpenChange.Set(path, emptyContainer(container)))
    }

    private fun closeContainer() {
        val frame = stack.removeLastOrNull() ?: return
        pathCost -= frame.container.size + 1
        dirty = true
        // Too deep to report inside: the whole value, final now that it is closed.
        if (frame.path != null && !frame.reportsInside) {
            report(OpenChange.Set(frame.path, frame.container.copy(emptyList())))
        }
        place(frame.slot, frame.container.copy(emptyList()))
        valueEnded(stack.lastOrNull())
    }

    /** A value in [frame], or the root value when there is no frame, has ended. */
    private fun valueEnded(frame: Frame?) {
        if (frame == null) {
            // Text after the root value is ignored, but only once a root value exists.
            if (root != null) done = true
            return
        }
        frame.phase = FramePhase.COMMA
    }

    /** Set the value at [slot] in the open path; nothing for a value in an invalid position. */
    private fun place(slot: Slot?, value: JsonElement) {
        if (slot == null) return
        dirty = true
        when (slot) {
            is Slot.Root -> root = value

            is Slot.Member -> {
                val members = (stack.last().container as Container.Members).members
                if (!members.containsKey(slot.key)) pathCost++
                members[slot.key] = value
            }

            is Slot.Element -> {
                val elements = (stack.last().container as Container.Elements).elements
                if (slot.index == elements.size) {
                    elements.add(value)
                    pathCost++
                } else {
                    elements[slot.index] = value
                }
            }
        }
    }

    /** A fresh snapshot: copies of the open containers, innermost first, each holding the copy of its open child. */
    private fun copyOpenPath(pending: Pending?): JsonElement? {
        if (stack.isEmpty()) {
            return if (pending != null && pending.slot is Slot.Root) pending.value else root
        }
        var child: JsonElement? = null
        var childSlot: Slot? = null
        for (depth in stack.lastIndex downTo 0) {
            val frame = stack[depth]
            val replacements = ArrayList<Pair<Slot, JsonElement>>(2)
            if (depth == stack.lastIndex &&
                pending != null
            ) {
                replacements.add(pending.slot to pending.value)
            }
            if (child != null && childSlot != null) replacements.add(childSlot to child)
            child = frame.container.copy(replacements)
            childSlot = frame.slot
        }
        // The outermost open container is the root unless it sits in an invalid position.
        return if (childSlot is Slot.Root) child else root
    }
}

/**
 * The arguments of one streaming tool call: the raw text, for the one full parse when the call ends, and a
 * [StreamingJsonParser] for the partial arguments in between. Providers keep one per call in their own stream state,
 * never on the content block, so the raw text never reaches partial messages or transcripts.
 */
class ToolCallArgumentsStream {
    private var rawText = ""
    private var parser = StreamingJsonParser()
    private var snapshot: JsonElement? = null
    private var arguments = JsonObject(emptyMap())

    /** The result of [replace]: the missing piece when the text continued, and its partial arguments. */
    data class Replacement(val delta: String?, val arguments: JsonObject)

    /** The raw argument text received so far. */
    val text: String get() = rawText

    /** Append a piece of argument text; returns the partial arguments, a new object whenever they changed. */
    fun append(delta: String): JsonObject {
        rawText += delta
        parser.append(delta)
        return partial()
    }

    /**
     * Take the provider's complete argument text. When it continues the text so far, returns the missing piece as
     * `delta`; otherwise starts over with [text]. Either way returns the partial arguments of [text].
     */
    fun replace(text: String): Replacement {
        if (text.startsWith(rawText)) {
            val delta = text.substring(rawText.length)
            return Replacement(delta, if (delta.isEmpty()) partial() else append(delta))
        }
        rawText = ""
        parser = StreamingJsonParser()
        return Replacement(null, append(text))
    }

    /** The final arguments: one full parse of the complete text, which reports malformed arguments. */
    fun finish(): ParsedToolCallArguments = parseToolCallArguments(rawText)

    private fun partial(): JsonObject {
        val snapshot = parser.snapshot()
        if (snapshot === this.snapshot) return arguments
        this.snapshot = snapshot
        // Tool arguments are an object; any other partial value shows as no arguments yet.
        arguments = snapshot as? JsonObject ?: JsonObject(emptyMap())
        return arguments
    }
}

private fun emptyContainer(container: Container): JsonElement =
    if (container is Container.Members) JsonObject(emptyMap()) else JsonArray(emptyList())

private fun isDelimiter(char: Char): Boolean = char == ' ' ||
    char == '\t' ||
    char == '\n' ||
    char == '\r' ||
    char == ',' ||
    char == ':' ||
    char == '{' ||
    char == '}' ||
    char == '[' ||
    char == ']' ||
    char == '"'

private fun isHexDigit(char: Char): Boolean =
    char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/** The value of a complete number or literal token; `null` for anything else, which is dropped. */
private fun scalarValue(token: String): JsonElement? {
    for ((literal, value) in LITERALS) if (token == literal) return value
    return if (NUMBER.matches(token)) JsonPrimitive(token.toDouble()) else null
}

/** The value an unterminated number or literal token shows. */
private fun pendingScalarValue(token: String): JsonElement? {
    for ((literal, value) in LITERALS) if (literal.startsWith(token)) return value
    if (NUMBER.matches(token)) return JsonPrimitive(token.toDouble())
    val mantissa = NUMBER_WITHOUT_EXPONENT_DIGITS.find(token) ?: return null
    return JsonPrimitive(mantissa.groupValues[1].toDouble())
}
