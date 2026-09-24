package works.resolve.pathfinder.codingagent.core.tools

private val UNICODE_SPACES = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")

/**
 * Resolve a path relative to the given cwd.
 *
 * Ports pi's `resolvePath` string normalizations in upstream order: unicode
 * space variants become " ", a leading "@" is stripped, and `file://` URLs
 * become paths (empty or `localhost` authority, percent-decoded, query and
 * fragment dropped) — then the result is joined under [cwd] and normalized
 * lexically like node's `path.resolve`, with no `java.nio` platform paths.
 * [cwd] gets the same `file://` conversion, the only part of pi's
 * default-option normalization of the base directory the port keeps.
 *
 * Divergences from pi:
 * - "~"/"~/" expansion is dropped: pi expands against the local home
 *   directory, the wrong machine over the operations seam, so "~" stays a
 *   literal relative segment.
 * - pi's `resolveReadPath` existence probes (macOS screenshot
 *   narrow-no-break-space, NFD, and curly-quote variants) are unported;
 *   through the operations seam existence is reported by `access`.
 * - Windows drive-path and separator handling is unported: paths are posix
 *   strings, absolute only when they start with "/" after normalization.
 */
fun resolveToCwd(filePath: String, cwd: String): String {
    val normalized = UNICODE_SPACES.replace(filePath, " ").let {
        if (it.startsWith("@")) it.substring(1) else it
    }
    val file = fileUrlToPath(normalized)
    val base = fileUrlToPath(cwd)
    return normalizePosixPath(if (file.startsWith("/")) file else "$base/$file")
}

/** Parent directory of a resolved posix path ("/" for top-level, "." for bare names). */
fun posixDirname(path: String): String = when (val idx = path.lastIndexOf('/')) {
    -1 -> "."
    0 -> "/"
    else -> path.substring(0, idx)
}

/** Node's posix `fileURLToPath`: localhost-only authority, decoded path. */
private fun fileUrlToPath(path: String): String {
    if (!path.startsWith("file://")) return path
    val rest = path.substring("file://".length)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = (if (authorityEnd == -1) rest else rest.substring(0, authorityEnd)).lowercase()
    require(host.isEmpty() || host == "localhost") { "File URL host is not localhost: $host" }
    val tail = rest.substring(if (authorityEnd == -1) rest.length else authorityEnd)
    val pathEnd = tail.indexOfFirst { it == '?' || it == '#' }
    val rawPath = if (pathEnd == -1) tail else tail.substring(0, pathEnd)
    // The WHATWG parser treats "\" as a separator in file URLs.
    return percentDecode(rawPath.replace('\\', '/')).ifEmpty { "/" }
}

/** Percent-decodes UTF-8 `%XX` escapes; malformed escapes are rejected.
 *
 * Node `fileURLToPath` semantics — unlike the loopback OAuth server's shared
 * `formQuery` (WHATWG `URLSearchParams`, which passes malformed escapes
 * through), a malformed path escape is a hard error. The asymmetry is
 * intentional: query parsing never fails upstream, file-URL decoding does.
 */
private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val input = value.toByteArray(Charsets.UTF_8)
    val decoded = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < input.size) {
        if (input[i] != '%'.code.toByte()) {
            decoded.write(input[i].toInt())
            i++
            continue
        }
        val hi = if (i + 2 < input.size) hexDigitValue(input[i + 1]) else -1
        val lo = if (hi >= 0) hexDigitValue(input[i + 2]) else -1
        require(hi >= 0 && lo >= 0) { "Malformed percent escape in $value" }
        decoded.write((hi shl 4) or lo)
        i += 3
    }
    return decoded.toString(Charsets.UTF_8)
}

private fun hexDigitValue(digit: Byte): Int = when (digit) {
    in '0'.code.toByte()..'9'.code.toByte() -> digit - '0'.code
    in 'a'.code.toByte()..'f'.code.toByte() -> digit - 'a'.code + 10
    in 'A'.code.toByte()..'F'.code.toByte() -> digit - 'A'.code + 10
    else -> -1
}

private fun normalizePosixPath(path: String): String {
    val absolute = path.startsWith("/")
    val segments = mutableListOf<String>()
    for (segment in path.split("/")) {
        when (segment) {
            "", "." -> {}

            // ".." pops the previous segment; at the root of an absolute path it
            // is dropped (posix normalize), and only relative paths keep leading
            // ".." segments.
            ".." -> if (segments.isNotEmpty() && segments.last() != "..") {
                segments.removeAt(segments.size - 1)
            } else if (!absolute) {
                segments.add(segment)
            }

            else -> segments.add(segment)
        }
    }
    val joined = segments.joinToString("/")
    return when {
        absolute -> "/$joined"
        joined.isEmpty() -> "."
        else -> joined
    }
}
