package works.resolve.pathfinder.codingagent.core.tools

/**
 * Resolve a path relative to the given cwd.
 *
 * Divergence from pi: resolution is posix-string-only, matching remote-cwd
 * semantics — paths are joined with "/" and normalized lexically, with no
 * `java.nio` platform paths. Pi's `~` expansion and the macOS
 * screenshot/NFD/curly-quote variants are unported: they probe the local
 * filesystem, and through the operations seam existence is reported by
 * `access` instead. [filePath] is absolute only when it starts with "/".
 */
fun resolveToCwd(filePath: String, cwd: String): String =
    normalizePosixPath(if (filePath.startsWith("/")) filePath else "$cwd/$filePath")

/** Parent directory of a resolved posix path ("/" for top-level, "." for bare names). */
fun posixDirname(path: String): String = when (val idx = path.lastIndexOf('/')) {
    -1 -> "."
    0 -> "/"
    else -> path.substring(0, idx)
}

private fun normalizePosixPath(path: String): String {
    val absolute = path.startsWith("/")
    val segments = mutableListOf<String>()
    for (segment in path.split("/")) {
        when (segment) {
            "", "." -> {}

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
