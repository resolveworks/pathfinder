package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ports pi's unicode-surrogate tests
 * (packages/ai/test/unicode-surrogate.test.ts @ b8b873b98).
 *
 * Upstream builds three tool-result payloads — a broad emoji/CJK/symbols
 * block, real-world LinkedIn comment data containing emoji, and text with an
 * intentionally unpaired high surrogate (0xD83D) — and round-trips each
 * through every provider's live API, asserting the request serializes
 * without a "no low surrogate in string" error. The provider dimension
 * collapses in a unit test: every pi serializer and every pathfinder adapter
 * funnels through the same shared [sanitizeSurrogates], so parity here means
 * the payloads verbatim, the surrogate-pairing structure they exercise, and
 * equivalence with pi's regex implementation over an exhaustive generated
 * surrogate table.
 */
class SanitizeUnicodeTest {

    @Test
    fun `paired surrogates are preserved`() {
        assertEquals("Hello 🙈 World", sanitizeSurrogates("Hello 🙈 World"))
    }

    @Test
    fun `unpaired high surrogate is removed`() {
        val unpaired = String(charArrayOf(0xD83D.toChar()))
        assertEquals("Text  here", sanitizeSurrogates("Text $unpaired here"))
    }

    @Test
    fun `unpaired low surrogate is removed`() {
        val unpaired = String(charArrayOf(0xDE48.toChar()))
        assertEquals("Text  here", sanitizeSurrogates("Text $unpaired here"))
    }

    // -----------------------------------------------------------------------
    // Upstream scenario 1: testEmojiInToolResults — tool-result text with
    // emoji, CJK, mathematical symbols, and quotes, verbatim from pi.
    // -----------------------------------------------------------------------

    private val emojiToolResultText = """
        Test with emoji 🙈 and other characters:
        - Monkey emoji: 🙈
        - Thumbs up: 👍
        - Heart: ❤️
        - Thinking face: 🤔
        - Rocket: 🚀
        - Mixed text: Mario Zechner wann? Wo? Bin grad äußersr eventuninformiert 🙈
        - Japanese: こんにちは
        - Chinese: 你好
        - Mathematical symbols: ∑∫∂√
        - Special quotes: "curly" 'quotes'
    """.trimIndent()

    @Test
    fun `upstream emoji tool-result payload is preserved verbatim`() {
        assertEquals(emojiToolResultText, sanitizeSurrogates(emojiToolResultText))
        assertNoUnpairedSurrogates(emojiToolResultText)
    }

    // -----------------------------------------------------------------------
    // Upstream scenario 2: testRealWorldLinkedInData — real-world LinkedIn
    // comment JSON with emoji, verbatim from pi (including the doubled space
    // after "Neumayer's").
    // -----------------------------------------------------------------------

    private val linkedInToolResultText = """
        Post: Hab einen "Generative KI für Nicht-Techniker" Workshop gebaut.
        Unanswered Comments: 2

        => {
          "comments": [
            {
              "author": "Matthias Neumayer's  graphic link",
              "text": "Leider nehmen das viel zu wenige Leute ernst"
            },
            {
              "author": "Matthias Neumayer's  graphic link",
              "text": "Mario Zechner wann? Wo? Bin grad äußersr eventuninformiert 🙈"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `upstream real-world LinkedIn payload is preserved verbatim`() {
        assertEquals(linkedInToolResultText, sanitizeSurrogates(linkedInToolResultText))
        assertNoUnpairedSurrogates(linkedInToolResultText)
    }

    // -----------------------------------------------------------------------
    // Upstream scenario 3: testUnpairedHighSurrogate — a string built with an
    // intentionally unpaired high surrogate (0xD83D), simulating corrupted
    // emoji. The sanitizer must strip it so providers accept the request.
    // -----------------------------------------------------------------------

    @Test
    fun `upstream unpaired high surrogate payload is sanitized`() {
        val unpaired = String(charArrayOf(0xD83D.toChar()))
        val payload = "Text with unpaired surrogate: $unpaired <- should be sanitized"
        val sanitized = sanitizeSurrogates(payload)
        assertEquals("Text with unpaired surrogate:  <- should be sanitized", sanitized)
        assertNoUnpairedSurrogates(sanitized)
    }

    @Test
    fun `astral and BMP characters from the upstream payload families are preserved`() {
        val preserved = listOf(
            "🙈", // U+1F648, see-no-evil monkey (paired surrogates)
            "👍", // U+1F44D
            "❤️", // U+2764 U+FE0F — heart + variation selector, both BMP
            "🤔", // U+1F914
            "🚀", // U+1F680
            "こんにちは",
            "你好",
            "∑∫∂√",
            "\"curly\" 'quotes'",
            "äußersr"
        )
        for (text in preserved) {
            assertEquals(text, sanitizeSurrogates(text))
        }
    }

    /**
     * Explicit boundary table for the surrogate ranges
     * (0xD800–0xDBFF high, 0xDC00–0xDFFF low), mirroring the pairing rule
     * of pi's regex: a high surrogate survives only when directly followed
     * by a low surrogate, and a low one only when directly preceded by a
     * high one.
     */
    @Test
    fun `surrogate boundary code points pair and drop like upstream's regex`() {
        val highStart = 0xD800.toChar()
        val highPayload = 0xD83D.toChar()
        val highEnd = 0xDBFF.toChar()
        val lowStart = 0xDC00.toChar()
        val lowPayload = 0xDE48.toChar() // low half of 🙈 (U+1F648)
        val lowEnd = 0xDFFF.toChar()

        val cases = listOf(
            Triple("boundary pair at range start", "$highStart$lowStart", "$highStart$lowStart"),
            Triple("boundary pair at range end", "$highEnd$lowEnd", "$highEnd$lowEnd"),
            Triple("payload pair forms 🙈", "$highPayload$lowPayload", "🙈"),
            Triple("unpaired high at string start", "${highStart}a", "a"),
            Triple("unpaired high at string end", "a$highPayload", "a"),
            Triple("unpaired high before BMP char", "$highPayload!", "!"),
            Triple("unpaired low at string start", "${lowStart}a", "a"),
            Triple("unpaired low after BMP char", "a$lowPayload", "a"),
            Triple(
                "second high pairs with trailing low",
                "$highStart$highPayload$lowPayload",
                "$highPayload$lowPayload"
            ),
            Triple("low then high are both dropped", "$lowPayload$highPayload", ""),
            Triple("pair chain stays intact", "🙈🙈", "🙈🙈")
        )
        for ((name, input, expected) in cases) {
            assertEquals(expected, sanitizeSurrogates(input), name)
        }
    }

    /**
     * Exhaustive generated table: every string up to length four over an
     * alphabet of a BMP char plus the surrogate-range boundary and payload
     * code points, compared against pi's own implementation
     * (packages/ai/src/utils/sanitize-unicode.ts @ b8b873b98).
     */
    @Test
    fun `matches pi's regex implementation on an exhaustive generated surrogate table`() {
        val table = generatedSurrogateTableStrings()
        assertTrue(table.isNotEmpty(), "table generator produced no cases")
        for (text in table) {
            assertEquals(
                sanitizeSurrogatesUpstream(text),
                sanitizeSurrogates(text),
                "divergence from pi's regex for input ${escapeSurrogates(text)}"
            )
        }
    }

    @Test
    fun `sanitization is idempotent and leaves no unpaired surrogates`() {
        val inputs = generatedSurrogateTableStrings() +
            listOf(emojiToolResultText, linkedInToolResultText, "Text \uD83D surrogate")
        for (text in inputs) {
            val sanitized = sanitizeSurrogates(text)
            assertNoUnpairedSurrogates(sanitized)
            assertEquals(
                sanitized,
                sanitizeSurrogates(sanitized),
                "not idempotent for ${escapeSurrogates(text)}"
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** pi's implementation: drop unpaired high/low surrogates via regex. */
    private fun sanitizeSurrogatesUpstream(text: String): String = text.replace(
        Regex("[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]"),
        ""
    )

    private val surrogateTableAlphabet = listOf(
        'a',
        0xD800.toChar(), // high surrogate range start
        0xD83D.toChar(), // upstream payload's orphaned high surrogate
        0xDBFF.toChar(), // high surrogate range end
        0xDC00.toChar(), // low surrogate range start
        0xDE48.toChar(), // low half of 🙈 (U+1F648)
        0xDFFF.toChar() // low surrogate range end
    )

    /** All strings of length 1..4 over [surrogateTableAlphabet] (2800 cases). */
    private fun generatedSurrogateTableStrings(): List<String> {
        val strings = mutableListOf<String>()
        fun generate(prefix: String, remaining: Int) {
            if (remaining == 0) return
            for (c in surrogateTableAlphabet) {
                strings += prefix + c
                generate(prefix + c, remaining - 1)
            }
        }
        generate("", 4)
        return strings
    }

    /**
     * The property providers enforce: a request string must not contain a
     * high surrogate without a directly following low surrogate, or a low
     * surrogate without a directly preceding high one ("no low surrogate in
     * string" JSON errors).
     */
    private fun assertNoUnpairedSurrogates(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                val paired = i + 1 < text.length && text[i + 1].isLowSurrogate()
                assertTrue(paired, "high surrogate at index $i is unpaired")
                i += 2
            } else {
                assertFalse(c.isLowSurrogate(), "low surrogate at index $i is unpaired")
                i += 1
            }
        }
    }

    private fun escapeSurrogates(text: String): String = buildString {
        for (c in text) {
            if (c.isSurrogate()) append("\\u%04X".format(c.code)) else append(c)
        }
    }
}
