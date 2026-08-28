package works.resolve.pathfinder.ai.utils

import com.github.luben.zstd.Zstd

/**
 * Pi's REQUEST_COMPRESSION_ZSTD_LEVEL (openai-codex-responses.ts:54): the
 * Codex backend accepts zstd-compressed request bodies on the SSE responses
 * endpoint (the same endpoint the official Codex client compresses against).
 */
internal const val REQUEST_COMPRESSION_ZSTD_LEVEL = 3

/**
 * Pi's compressRequestBodyZstd (openai-codex-responses.ts:208-223): compress
 * the serialized request body at [REQUEST_COMPRESSION_ZSTD_LEVEL] and return
 * the compressed bytes, or null when compression is unavailable or fails —
 * callers fall back to sending the uncompressed JSON. Never throws.
 *
 * Upstream uses Node's `zlib.zstdCompressSync` and returns null in
 * browser/Vite builds where the module is missing; on the JVM the equivalent
 * unavailability is the zstd-jni native library failing to load, so failures
 * (including linkage errors) are caught here. Never logs the body.
 */
fun compressRequestBodyZstd(
    bodyJson: String,
    level: Int = REQUEST_COMPRESSION_ZSTD_LEVEL,
): ByteArray? = try {
    Zstd.compress(bodyJson.toByteArray(Charsets.UTF_8), level)
} catch (_: Throwable) {
    null
}
