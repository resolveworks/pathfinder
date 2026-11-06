package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class StreamingJsonTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun assertJson(expected: JsonElement?, actual: JsonElement?, message: String = "") {
        if (expected == null) {
            assertNull(actual, message)
            return
        }
        assertNotNull(actual, message)
        assertTrue(jsonEquals(expected, actual), "$message: expected $expected but was $actual")
    }

    private fun stream(text: String, chunkSize: Int): List<JsonElement?> {
        val parser = StreamingJsonParser()
        val snapshots = mutableListOf<JsonElement?>()
        var index = 0
        while (index < text.length) {
            val end = minOf(index + chunkSize, text.length)
            parser.append(text.substring(index, end))
            index = end
            snapshots.add(parser.snapshot())
        }
        return snapshots
    }

    private fun finalSnapshot(
        text: String,
        chunkSize: Int = text.length.coerceAtLeast(1)
    ): JsonElement? = stream(text, chunkSize).lastOrNull()

    // --- Ported vectors: partial-json and Vercel fixJson public test suites ---

    private val partialJsonVectors = listOf(
        "\"" to "\"\"",
        "[\"" to "[\"\"]",
        "{\"\": \"" to "{\"\":\"\"}",
        "n" to "null",
        "t" to "true",
        "f" to "false",
        "0" to "0",
        "-1.25e+4" to "-12500",
        "-1.25e+" to "-1.25",
        "-1.25e" to "-1.25",
        // Differences from partial-json: an invalid escape stays visible, and `Infinity`/`NaN` are dropped.
        "\" \\x12" to "\" \\\\x12\"",
        "I" to null,
        "-I" to null,
        "N" to null
    )

    private val fixJsonVectors = listOf(
        "" to null,
        "nul" to "null",
        "t" to "true",
        "fals" to "false",
        "12.2" to "12.2",
        "-12" to "-12",
        "-" to null,
        "2.5e" to "2.5",
        "2.5e-" to "2.5",
        "2.5e3" to "2500",
        "-2.5e3" to "-2500",
        "2.5E" to "2.5",
        "2.5E-" to "2.5",
        "2.5E3" to "2500",
        "-2.5E3" to "-2500",
        "12.34e" to "12.34",
        "5e" to "5",
        "\"abc" to "\"abc\"",
        "\"value with \\\"quoted\\\" text and \\\\ escape" to
            "\"value with \\\"quoted\\\" text and \\\\ escape\"",
        "\"value with \\" to "\"value with \"",
        "\"\\u" to "\"\"",
        "\"\\u12" to "\"\"",
        "\"text \\u00" to "\"text \"",
        "{\"a\":\"\\u12" to "{\"a\":\"\"}",
        "\"value with unicode <\"" to "\"value with unicode <\"",
        "[" to "[]",
        "[[1], [2" to "[[1],[2]]",
        "[[\"1\"], [\"2" to "[[\"1\"],[\"2\"]]",
        "[[false], [nu" to "[[false],[null]]",
        "[[[]], [[]" to "[[[]],[[]]]",
        "[[{}], [{" to "[[{}],[{}]]",
        "[1, " to "[1]",
        "[[], 123" to "[[],123]",
        "{\"key\":" to "{}",
        "{\"a\": {\"b\": 1}, \"c\": {\"d\": 2" to "{\"a\":{\"b\":1},\"c\":{\"d\":2}}",
        "{\"a\": {\"b\": \"1\"}, \"c\": {\"d\": 2" to "{\"a\":{\"b\":\"1\"},\"c\":{\"d\":2}}",
        "{\"a\": {\"b\": false}, \"c\": {\"d\": 2" to "{\"a\":{\"b\":false},\"c\":{\"d\":2}}",
        "{\"a\": {\"b\": []}, \"c\": {\"d\": 2" to "{\"a\":{\"b\":[]},\"c\":{\"d\":2}}",
        "{\"a\": {\"b\": {}}, \"c\": {\"d\": 2" to "{\"a\":{\"b\":{}},\"c\":{\"d\":2}}",
        "{\"ke" to "{}",
        "{\"k1\": 1, \"k2" to "{\"k1\":1}",
        "{\"k1\": 1, \"k2\":" to "{\"k1\":1}",
        "{\"key\": \"value\"  " to "{\"key\":\"value\"}",
        "{\"a\": {\"b\": {}" to "{\"a\":{\"b\":{}}}",
        "[1, [2, 3, [" to "[1,[2,3,[]]]",
        "[false, [true, [" to "[false,[true,[]]]",
        "{\"key\": {\"subKey\":" to "{\"key\":{}}",
        "{\"key\": 123, \"key2\": {\"subKey\":" to "{\"key\":123,\"key2\":{}}",
        "{\"key\": null, \"key2\": {\"subKey\":" to "{\"key\":null,\"key2\":{}}",
        "{\"key\": [1, 2, {" to "{\"key\":[1,2,{}]}",
        "[1, 2, {\"key\": \"value\"," to "[1,2,{\"key\":\"value\"}]",
        "{\"a\": {\"b\": [\"c\", {\"d\": \"e\"," to "{\"a\":{\"b\":[\"c\",{\"d\":\"e\"}]}}",
        "{\"a\": {\"b\": {\"c\": {\"d\":" to "{\"a\":{\"b\":{\"c\":{}}}}",
        "{\"a\": 1, \"b\": [" to "{\"a\":1,\"b\":[]}",
        "{\"a\": 1, \"b\": {" to "{\"a\":1,\"b\":{}}",
        "{\"a\": 1, \"b\": \"" to "{\"a\":1,\"b\":\"\"}",
        "{\n  \"a\": [\n    {\n      \"a1\": \"v1\",\n      \"a2\": \"v2\",\n" +
            "      \"a3\": \"v3\"\n    }\n  ],\n  \"b\": [\n    {\n      \"b1\": \"n" to
            "{\"a\":[{\"a1\":\"v1\",\"a2\":\"v2\",\"a3\":\"v3\"}],\"b\":[{\"b1\":\"n\"}]}",
        "{\"type\":\"div\",\"children\":[{\"type\":\"Card\",\"props\":{}" to
            "{\"type\":\"div\",\"children\":[{\"type\":\"Card\",\"props\":{}}]}",
        // Difference: fixJson completes `12.` to 12; like parseStreamingJson, a number cut after its dot is omitted.
        "12." to null,
        "12.e" to null
    )

    @Test
    fun readsPortedVectors() {
        for ((input, expected) in partialJsonVectors + fixJsonVectors) {
            val expectedJson = expected?.let(::json)
            for (size in setOf(input.length.coerceAtLeast(1), 1, 2)) {
                assertJson(expectedJson, finalSnapshot(input, size), "$input (chunk size $size)")
            }
        }
    }

    // --- Strings ---

    @Test
    fun holdsAnEscapeBackUntilItIsCompleteAtEverySplitPosition() {
        val cases = listOf(
            "{\"a\":\"x\\u00e9y\"}" to "xéy",
            "{\"a\":\"x\\ny\"}" to "x\ny",
            "{\"a\":\"x\\\\y\"}" to "x\\y",
            "{\"a\":\"x\\\"y\"}" to "x\"y",
            "{\"a\":\"x\\/y\"}" to "x/y"
        )
        for ((text, value) in cases) {
            for (split in 0..text.length) {
                val parser = StreamingJsonParser()
                parser.append(text.substring(0, split))
                parser.snapshot()
                parser.append(text.substring(split))
                assertJson(
                    JsonObject(mapOf("a" to JsonPrimitive(value))),
                    parser.snapshot(),
                    text.substring(0, split)
                )
            }
        }
    }

    @Test
    fun joinsSurrogatePairsSplitAcrossPiecesEscapedOrRaw() {
        val escaped = "{\"a\":\"\\ud83d\\ude00!\"}"
        val raw = "{\"a\":\"\uD83D\uDE00!\"}"
        for (text in listOf(escaped, raw)) {
            for (split in 0..text.length) {
                val parser = StreamingJsonParser()
                parser.append(text.substring(0, split))
                parser.snapshot()
                parser.append(text.substring(split))
                assertJson(json("{\"a\":\"\uD83D\uDE00!\"}"), parser.snapshot())
            }
        }
        // A high surrogate whose low half has not arrived shows alone, like JSON.parse() of the escape.
        val parser = StreamingJsonParser()
        parser.append("{\"a\":\"\\ud83d")
        assertJson(JsonObject(mapOf("a" to JsonPrimitive("\uD83D"))), parser.snapshot())
    }

    @Test
    fun keepsTrailingWhitespaceOfAnUnterminatedString() {
        val parser = StreamingJsonParser()
        parser.append("{\"code\":\"x\t\t  ")
        assertJson(JsonObject(mapOf("code" to JsonPrimitive("x\t\t  "))), parser.snapshot())
    }

    @Test
    fun keepsRawControlCharactersAndInvalidEscapesVisibleWhileStreaming() {
        val parser = StreamingJsonParser()
        parser.append("{\"a\":\"x\ny \\d \\u12\"")
        assertJson(json("{\"a\":\"x\\ny \\\\d \\\\u12\"}"), parser.snapshot())
    }

    @Test
    fun keepsInvalidUnicodeEscapesLiteralWithoutDroppingFields() {
        assertJson(
            json("{\"path\":\"a.tex\",\"content\":\"\\\\usepackage{x}\",\"mode\":\"w\"}"),
            finalSnapshot("{\"path\":\"a.tex\",\"content\":\"\\usepackage{x}\",\"mode\":\"w\"}", 1)
        )
        assertJson(
            json("{\"text\":\"café \\\\url\"}"),
            finalSnapshot("{\"text\":\"caf\\u00e9 \\url\"}", 3)
        )
        val parser = StreamingJsonParser()
        parser.append("{\"text\":\"ab\\u00")
        assertJson(json("{\"text\":\"ab\"}"), parser.snapshot())
    }

    @Test
    fun keepsTheLastValueOfADuplicateKeyInTheFirstKeysPosition() {
        val text = "{\"a\":1,\"b\":2,\"a\":\"three\"}"
        val value = finalSnapshot(text, 1) as JsonObject
        assertEquals(listOf("a", "b"), value.keys.toList())
        assertJson(json(text), value)
    }

    @Test
    fun keepsUnderscoreProtoAsAnOwnMember() {
        val text = "{\"__proto__\":{\"polluted\":true},\"b\":{\"__proto__\":\"x\"}}"
        for (split in 0..text.length) {
            val parser = StreamingJsonParser()
            parser.append(text.substring(0, split))
            parser.snapshot()
            parser.append(text.substring(split))
            val value = parser.snapshot() as JsonObject
            assertJson(json(text), value)
            assertTrue(value.containsKey("__proto__"))
        }
    }

    // --- Malformed text ---

    private val malformed = listOf(
        "{\"a\":1 \"b\":2}",
        "{\"a\" 1, \"b\": 2}",
        "{a:1, 'b': 2}",
        "{\"a\":1,,\"b\":2,}",
        "[1 2, , 3,]",
        "{\"a\":[1,2}, \"b\":3]",
        "]]]}}}{",
        "{\"a\":NaN,\"b\":Infinity,\"c\":-Infinity,\"d\":undefined,\"e\":1}",
        "{\"a\":01,\"b\":1.,\"c\":.5,\"d\":+1,\"e\":0x10,\"f\":1e}",
        "{\"a\":\"x\"} trailing garbage {\"b\":2}",
        "{\"a\": tru, \"b\": nulll, \"c\": falsey}",
        "{\"a\":{\"b\":{\"c\":",
        "{\"a\\\": 1}",
        "\"unterminated \\",
        "// comment\n{\"a\":1}",
        "\u0000{\u0001\"a\"\u0002:\u00031}",
        "{\"a\":\"x\"}}}]]]"
    )

    private fun assertPlainJson(value: JsonElement?, label: String) {
        when (value) {
            null, is JsonNull -> return

            is JsonObject -> value.values.forEach { assertPlainJson(it, label) }

            is JsonArray -> value.forEach { assertPlainJson(it, label) }

            is JsonPrimitive -> if (!value.isString && value.strictBooleanOrNull() == null) {
                val number = value.strictDoubleOrNull()
                assertTrue(number != null && number.isFinite(), label)
            }
        }
    }

    @Test
    fun neverThrowsOnMalformedTextAndReadsItTheSameAtEveryChunkSize() {
        for (text in malformed) {
            val expected = finalSnapshot(text)
            for (size in listOf(text.length.coerceAtLeast(1), 1, 2, 3, 7)) {
                val snapshots = stream(text, size)
                for (snapshot in snapshots) assertPlainJson(snapshot, text)
                assertJson(expected, snapshots.lastOrNull(), "$text (chunk size $size)")
            }
        }
    }

    @Test
    fun toleratesMissingCommasAndColonsAndDropsValuesInInvalidPositions() {
        assertJson(json("{\"a\":1,\"b\":2}"), finalSnapshot("{\"a\":1 \"b\":2}"))
        assertJson(json("{\"a\":1,\"b\":2}"), finalSnapshot("{\"a\" 1, \"b\": 2}"))
        assertJson(json("[1,2,3]"), finalSnapshot("[1 2, , 3,]"))
        assertJson(json("{\"e\":1}"), finalSnapshot("{\"a\":NaN,\"b\":Infinity,\"e\":1}"))
        assertJson(json("{\"a\":\"x\"}"), finalSnapshot("{\"a\":\"x\"} trailing garbage {\"b\":2}"))
    }

    // --- Snapshot immutability ---

    private fun deepCopy(value: JsonElement?): JsonElement? = when (value) {
        null, is JsonNull -> value
        is JsonObject -> JsonObject(value.mapValues { deepCopy(it.value)!! })
        is JsonArray -> JsonArray(value.map { deepCopy(it)!! })
        is JsonPrimitive -> value
    }

    @Test
    fun neverChangesASnapshotOnceItIsReturned() {
        val documents = listOf(
            "{\"path\":\"a.ts\",\"args\":[1,{\"b\":\"c\\u00e9\"},true,null],\"n\":-1.5e3}",
            "{\"a\":{\"b\":{\"c\":{\"d\":\"deep\"}}}}",
            "[1,[2,[3,[4,[5]]]]]",
            "{\"text\":\"x\ny \\\\d \\u12\",\"n\":12."
        )
        for (text in documents) {
            for (size in listOf(1, 2, 3, 7)) {
                val parser = StreamingJsonParser()
                val kept = mutableListOf<Pair<JsonElement?, JsonElement?>>()
                var index = 0
                while (index < text.length) {
                    val end = minOf(index + size, text.length)
                    parser.append(text.substring(index, end))
                    index = end
                    val snapshot = parser.snapshot()
                    kept.add(snapshot to deepCopy(snapshot))
                }
                for ((snapshot, copy) in kept) assertJson(copy, snapshot, text)
            }
        }
    }

    // --- Changes ---

    @Test
    fun reportsANumberThatEndsAndAStringThatStartsInOnePieceInOrder() {
        val parser = StreamingJsonParser()
        assertEquals(
            listOf(JsonChange.Set(emptyList(), JsonObject(emptyMap()))),
            parser.append("{\"n\":1")
        )
        assertEquals(
            listOf(
                JsonChange.Set(listOf(JsonPathSegment.Key("n")), JsonPrimitive(12.0)),
                JsonChange.Set(listOf(JsonPathSegment.Key("s")), JsonPrimitive("ab"))
            ),
            parser.append("2,\"s\":\"ab")
        )
        assertEquals(
            listOf(JsonChange.Append(listOf(JsonPathSegment.Key("s")), "c")),
            parser.append("c\",\"t\":tru")
        )
        assertEquals(
            listOf(JsonChange.Set(listOf(JsonPathSegment.Key("t")), JsonPrimitive(true))),
            parser.append("e}")
        )
        assertEquals(emptyList<JsonChange>(), parser.append(" {\"ignored\":1}"))
    }

    @Test
    fun reportsNestedObjectsAndArraysAsEmptyContainersThenTheirMembersAndItems() {
        val parser = StreamingJsonParser()
        assertEquals(
            listOf(
                JsonChange.Set(emptyList(), JsonObject(emptyMap())),
                JsonChange.Set(listOf(JsonPathSegment.Key("a")), JsonArray(emptyList())),
                JsonChange.Set(
                    listOf(JsonPathSegment.Key("a"), JsonPathSegment.Index(0)),
                    JsonPrimitive(1.0)
                ),
                JsonChange.Set(
                    listOf(JsonPathSegment.Key("a"), JsonPathSegment.Index(1)),
                    JsonObject(emptyMap())
                ),
                JsonChange.Set(
                    listOf(
                        JsonPathSegment.Key("a"),
                        JsonPathSegment.Index(1),
                        JsonPathSegment.Key("b")
                    ),
                    JsonArray(emptyList())
                ),
                JsonChange.Set(
                    listOf(
                        JsonPathSegment.Key("a"),
                        JsonPathSegment.Index(1),
                        JsonPathSegment.Key("b"),
                        JsonPathSegment.Index(0)
                    ),
                    JsonPrimitive("x")
                )
            ),
            parser.append("{\"a\":[1,{\"b\":[\"x\"")
        )
        assertEquals(
            listOf(
                JsonChange.Set(
                    listOf(
                        JsonPathSegment.Key("a"),
                        JsonPathSegment.Index(1),
                        JsonPathSegment.Key("b"),
                        JsonPathSegment.Index(1)
                    ),
                    JsonPrimitive("y")
                )
            ),
            parser.append(",\"y")
        )
        assertEquals(
            listOf(
                JsonChange.Append(
                    listOf(
                        JsonPathSegment.Key("a"),
                        JsonPathSegment.Index(1),
                        JsonPathSegment.Key("b"),
                        JsonPathSegment.Index(1)
                    ),
                    "z"
                ),
                JsonChange.Set(listOf(JsonPathSegment.Key("a")), JsonPrimitive("again"))
            ),
            parser.append("z\"]}],\"a\":\"again\"}")
        )
    }

    @Test
    fun holdsSplitEscapesBackAndReportsTheirDecodedTextOnceComplete() {
        val parser = StreamingJsonParser()
        parser.append("{\"a\":\"x")
        assertEquals(emptyList<JsonChange>(), parser.append("\\u00"))
        assertEquals(
            listOf(JsonChange.Append(listOf(JsonPathSegment.Key("a")), "é")),
            parser.append("e9")
        )
    }

    @Test
    fun reportsAContainerNestedDeeperThanTheReportLimitOnceWholeWhenItCloses() {
        val depth = 40
        val text = "{\"a\":".repeat(depth) + "\"deep\"" + "}".repeat(depth)
        val changes = StreamingJsonParser().append(text)
        assertEquals(34, changes.size)
        val last = assertIs<JsonChange.Set>(changes.last())
        assertEquals(32, last.path.size)
        assertJson(json(text.substring(32 * 5, text.length - 32)), last.value)
    }

    @Test
    fun reportsNothingForValuesInInvalidPositionsAndTextAfterTheRoot() {
        val parser = StreamingJsonParser()
        assertEquals(
            listOf(
                JsonChange.Set(emptyList(), JsonObject(emptyMap())),
                JsonChange.Set(listOf(JsonPathSegment.Key("a")), JsonPrimitive(1.0)),
                JsonChange.Set(listOf(JsonPathSegment.Key("b")), JsonPrimitive("x"))
            ),
            parser.append("{\"a\":1 2, \"b\" \"x\"}")
        )
        assertEquals(emptyList<JsonChange>(), parser.append("{\"c\":3}"))
    }

    // --- ToolCallArgumentsStream ---

    @Test
    fun showsPartialArgumentsAsAnObjectAndFinishesWithOneFullParse() {
        val args = ToolCallArgumentsStream()
        assertEquals(JsonObject(emptyMap()), args.append("  "))
        assertEquals(
            json("{\"path\":\"a.ts\",\"content\":\"x\"}"),
            args.append("{\"path\":\"a.ts\",\"content\":\"x")
        )
        val before = args.append("y")
        assertSame(before, args.append(""))
        assertEquals("  {\"path\":\"a.ts\",\"content\":\"xy", args.text)
        assertEquals(parseToolCallArguments("  {\"path\":\"a.ts\",\"content\":\"xy"), args.finish())
        args.append("\"}")
        assertEquals(
            parseToolCallArguments("{\"path\":\"a.ts\",\"content\":\"xy\"}"),
            args.finish()
        )
    }

    @Test
    fun showsNonObjectRootsAsNoArgumentsYet() {
        val args = ToolCallArgumentsStream()
        assertEquals(JsonObject(emptyMap()), args.append("[1,2"))
        args.append("]")
        val error = args.finish().argumentsError
        assertNotNull(error)
        assertTrue(error.startsWith("Tool arguments must be a JSON object"), error)
    }

    @Test
    fun replacesTheTextWithTheProvidersCompleteTextReturningTheMissingPiece() {
        val args = ToolCallArgumentsStream()
        args.append("{\"a\":\"x")
        val continued = args.replace("{\"a\":\"xyz\"}")
        assertEquals("yz\"}", continued.delta)
        assertJson(json("{\"a\":\"xyz\"}"), continued.arguments)
        val restarted = args.replace("{\"b\":2}")
        assertNull(restarted.delta)
        assertJson(json("{\"b\":2}"), restarted.arguments)
        assertEquals("{\"b\":2}", args.text)
    }

    @Test
    fun streamsALongStringValueInSmallDeltas() {
        val content = (0 until 20_000).joinToString("") {
            "const line$it = \"value $it\"; // \\ done\n"
        }
        val text = "{\"path\":\"/src/file.ts\",\"content\":" + JsonPrimitive(content) + "}"
        val args = ToolCallArgumentsStream()
        var index = 0
        while (index < text.length) {
            val end = minOf(index + 32, text.length)
            args.append(text.substring(index, end))
            index = end
        }
        val finished = args.finish()
        assertEquals(content, finished.arguments.getValue("content").jsonPrimitive.content)
    }
}
