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
 * the compressed bytes, or null when compression fails — callers fall back to
 * sending the uncompressed JSON, exactly as pi falls back when
 * `zlib.zstdCompressSync` throws. Never throws; never logs the body.
 *
 * Divergence: pi's other null case — Node's zlib being absent entirely
 * (browser/Vite builds) — is not modeled. zstd-jni is an ordinary runtime
 * dependency on Android; a native-library failure is simply a compression
 * failure that falls back to the uncompressed request.
 */
fun compressRequestBodyZstd(
    bodyJson: String,
    level: Int = REQUEST_COMPRESSION_ZSTD_LEVEL,
): ByteArray? = try {
    Zstd.compress(bodyJson.toByteArray(Charsets.UTF_8), level)
} catch (_: Throwable) {
    null
}
