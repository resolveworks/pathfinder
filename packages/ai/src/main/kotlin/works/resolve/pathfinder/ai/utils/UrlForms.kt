package works.resolve.pathfinder.ai.utils

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WHATWG `application/x-www-form-urlencoded` serialization over
 * [java.net.URLEncoder.encode] — byte-identical to JS
 * `new URLSearchParams(fields).toString()` for our inputs: unreserved ASCII
 * (`A-Z a-z 0-9 - _ . *`) stays bare, space becomes `+`, everything else is
 * percent-encoded as UTF-8. OkHttp's `addQueryParameter` is NOT a form
 * encoder (space becomes `%20`); do not substitute it.
 *
 * Divergence, adversarial-only: an unpaired surrogate encodes as literal `?`
 * where JS encodes U+FFFD as `%EF%BF%BD`; every field encoded here is
 * self-constructed OAuth input, never a code point the JVM string can only
 * hold as a lone surrogate.
 */
internal fun formUrlEncode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (name, value) ->
        encodeFormField(name) + "=" + encodeFormField(value)
    }

private fun encodeFormField(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)

/**
 * WHATWG query parsing with `URLSearchParams.get` semantics over a
 * WHATWG-derived [HttpUrl] parse: first occurrence of a name wins, `+`
 * decodes as space, malformed percent escapes (`%ZZ`, a trailing `%`) pass
 * through per escape sequence, and invalid UTF-8 decodes to U+FFFD. A name
 * without `=` (`?a`) reads as the empty string — `queryParameter` reports a
 * bare name's value as null, distinguishable via `queryParameterNames`.
 * URLSearchParams never fails, and query-only input cannot break the HttpUrl
 * parse (control characters and raw whitespace are stripped or encoded), so
 * a hypothetical parse failure reads as no parameters.
 *
 * Divergence, adversarial-only: a percent-encoded surrogate sequence decodes
 * to one U+FFFD where WHATWG yields three.
 */
internal fun formQuery(rawQuery: String): Map<String, String> {
    if (rawQuery.isEmpty()) return emptyMap()
    return "http://localhost/?$rawQuery".toHttpUrlOrNull()?.let(::formQuery) ?: emptyMap()
}

private fun formQuery(url: HttpUrl): Map<String, String> {
    val params = LinkedHashMap<String, String>(url.queryParameterNames.size)
    for (name in url.queryParameterNames) {
        params[name] = url.queryParameter(name) ?: ""
    }
    return params
}

/**
 * Query parameters of an absolute-URL value, or null when it is not an
 * absolute URL — pi's `new URL(value)` gate. WHATWG tolerates malformed
 * percent escapes that java.net.URI rejects, so a URI parse failure retries
 * through the WHATWG-derived [HttpUrl] before the value falls through to the
 * flow's non-URL branches.
 */
internal fun urlQueryParamsOrNull(value: String): Map<String, String>? = try {
    val uri = URI(value)
    if (uri.scheme == null) null else uri.rawQuery?.let { formQuery(it) } ?: emptyMap()
} catch (_: Exception) {
    value.toHttpUrlOrNull()?.let(::formQuery)
}

/**
 * WHATWG `new URL(raw).href` normalization for http(s) URLs: lowercase scheme
 * and host, default ports dropped, empty path `/`, userinfo and special-form
 * (`https:foo`) handling included. Non-http(s) schemes and unparseable values
 * return null — exactly the http(s)-only gate the OAuth flows want.
 */
internal fun normalizedHttpUrlOrNull(raw: String): String? = raw.toHttpUrlOrNull()?.toString()

/**
 * Decodes the base64url payload of a `header.payload.signature` JWT to a
 * JSON object: the token must have exactly three `.`-separated parts, the
 * payload is RFC 7515 base64url with omitted padding tolerated, and the
 * decoded bytes must be a JSON object. Any other shape returns null.
 */
internal fun decodeJwtPayload(token: String): JsonObject? = try {
    val parts = token.split(".")
    if (parts.size != 3) {
        null
    } else {
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        lenientJson.parseToJsonElement(Base64.getUrlDecoder().decode(padded).decodeToString())
            as? JsonObject
    }
} catch (_: Exception) {
    null
}
