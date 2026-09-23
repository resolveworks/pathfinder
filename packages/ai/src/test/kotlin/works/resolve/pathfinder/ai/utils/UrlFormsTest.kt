package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlFormsTest {
    @Test
    fun `formQuery matches URLSearchParams get semantics`() {
        // Bare names read as the empty string, with or without a later value.
        assertEquals(mapOf("a" to "", "b" to "1"), formQuery("a&b=1"))
        assertEquals(mapOf("a" to ""), formQuery("a"))
        assertEquals(mapOf("a" to ""), formQuery("a&a=1"))
        assertEquals(mapOf("a" to ""), formQuery("a="))
        // First occurrence wins.
        assertEquals(mapOf("a" to "1"), formQuery("a=1&a=2"))
        // `+` decodes as space; an escaped plus stays a plus.
        assertEquals(mapOf("x" to "a b"), formQuery("x=a+b"))
        assertEquals(mapOf("x" to "+"), formQuery("x=%2B"))
        // Names decode too.
        assertEquals(mapOf("a b" to "1"), formQuery("a+b=1"))
        assertEquals(mapOf("code" to "1"), formQuery("%63ode=1"))
        // Empty query.
        assertEquals(emptyMap(), formQuery(""))
    }

    @Test
    fun `malformed percent escapes pass through per sequence`() {
        assertEquals(mapOf("x" to "%ZZé"), formQuery("x=%ZZ%C3%A9"))
        assertEquals(mapOf("x" to "ab%zz"), formQuery("x=ab%zz"))
        assertEquals(mapOf("x" to "100%"), formQuery("x=100%"))
        assertEquals(mapOf("x" to "%"), formQuery("x=%"))
        assertEquals(mapOf("x" to "%2"), formQuery("x=%2"))
        // Invalid UTF-8 decodes to U+FFFD.
        assertEquals(mapOf("x" to "\uFFFD"), formQuery("x=%FF"))
        assertEquals(mapOf("x" to "é"), formQuery("x=%C3%A9"))
    }

    @Test
    fun `a surrogate escape sequence decodes to one replacement character`() {
        // URLSearchParams.get would yield three U+FFFD here; the accepted
        // WHATWG divergence from the HttpUrl decode path is one.
        assertEquals(mapOf("x" to "\uFFFD"), formQuery("x=%ED%A0%80"))
    }

    @Test
    fun `query input never throws`() {
        // Anything URLSearchParams tolerates stays parseable: raw space,
        // tabs (WHATWG strips them), structural characters, NUL bytes.
        assertEquals(mapOf("a b" to "1"), formQuery("a b=1"))
        assertEquals(mapOf("ab" to "1"), formQuery("a\tb=1"))
        assertEquals(
            mapOf("a" to "{b}|^`\"<>\\", "?" to "1"),
            formQuery("a={b}|^`\"<>\\&?=1")
        )
        assertEquals(mapOf("a\u0000b" to "1"), formQuery("a\u0000b=1"))
    }

    @Test
    fun `formUrlEncode matches URLSearchParams serialization`() {
        assertEquals("x=%7E+*", formUrlEncode(mapOf("x" to "~ *")))
        assertEquals("a+b=c%2Fd", formUrlEncode(mapOf("a b" to "c/d")))
        // Unreserved set: alphanumerics, `*`, `-`, `.`, `_` stay bare; every
        // other printable ASCII byte percent-encodes (verified against
        // `new URLSearchParams({k: set}).toString()` for the whole set).
        assertEquals(
            "k=%21%22%23%24%25%26%27%28%29*%2B%2C-.%2F0123456789" +
                "%3A%3B%3C%3D%3E%3F%40ABCDEFGHIJKLMNOPQRSTUVWXYZ%5B%5C%5D%5E_%60" +
                "abcdefghijklmnopqrstuvwxyz%7B%7C%7D%7E",
            formUrlEncode(
                mapOf(
                    "k" to "!\"#\$%&'()*+,-./0123456789:;<=>?@" +
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
                )
            )
        )
        assertEquals("u=%C3%BC", formUrlEncode(mapOf("u" to "ü")))
        assertEquals("e=%E4%B8%AD", formUrlEncode(mapOf("e" to "中")))
        // Supplementary code points encode as their full UTF-8 sequence.
        assertEquals("e=%F0%9F%98%80", formUrlEncode(mapOf("e" to "\uD83D\uDE00")))
        // Insertion order and multiple fields.
        assertEquals("a=1&b=2&c=3", formUrlEncode(linkedMapOf("a" to "1", "b" to "2", "c" to "3")))
    }

    @Test
    fun `normalizedHttpUrlOrNull matches new URL href for http and https`() {
        assertEquals("https://example.com/", normalizedHttpUrlOrNull("https://example.com"))
        assertEquals("http://example.com/", normalizedHttpUrlOrNull("http://example.com:80/"))
        assertEquals("https://example.com/", normalizedHttpUrlOrNull("HTTPS://EXAMPLE.com:443"))
        assertEquals(
            "https://User@example.com/",
            normalizedHttpUrlOrNull("https://User@Example.COM:443")
        )
        assertEquals(
            "https://example.com/Path?q=1#f",
            normalizedHttpUrlOrNull("https://example.com/Path?q=1#f")
        )
        assertEquals("https://foo/", normalizedHttpUrlOrNull("https:foo"))
        assertEquals(
            "https://example.com/a%20b",
            normalizedHttpUrlOrNull("https://example.com/a b")
        )
        // Non-http(s) schemes and unparseable values are the null gate.
        assertNull(normalizedHttpUrlOrNull("mailto:x@y"))
        assertNull(normalizedHttpUrlOrNull("file:///bin/sh"))
        assertNull(normalizedHttpUrlOrNull("https://"))
        assertNull(normalizedHttpUrlOrNull("not a url"))
    }

    @Test
    fun `decodeJwtPayload decodes unpadded base64url payloads to JSON objects`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"u","chatgpt_account_id":"acc-1"}""".toByteArray())
        val token = "header.$payload.signature"
        val decoded = decodeJwtPayload(token)
        assertEquals("acc-1", decoded?.str("chatgpt_account_id"))
    }

    @Test
    fun `decodeJwtPayload rejects non-jwt and non-object shapes`() {
        assertNull(decodeJwtPayload("not-a-jwt"))
        assertNull(decodeJwtPayload("a.b"))
        assertNull(decodeJwtPayload("a.b.c.d"))
        val stringPayload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("\"scalar\"".toByteArray())
        assertNull(decodeJwtPayload("h.$stringPayload.s"))
        val badBase64 = "h.!!!.s"
        assertNull(decodeJwtPayload(badBase64))
        val badJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{not json".toByteArray())
        assertNull(decodeJwtPayload("h.$badJson.s"))
    }
}
