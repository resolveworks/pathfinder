package works.resolve.pathfinder.ai.utils

import com.github.luben.zstd.Zstd

/**
 * The Codex backend accepts zstd-compressed request bodies on the SSE
 * responses endpoint (the same endpoint the official Codex client compresses
 * against).
 */
internal const val REQUEST_COMPRESSION_ZSTD_LEVEL = 3

/**
 * Compresses the serialized request body, or returns null when compression
 * fails — callers fall back to sending the uncompressed JSON, as pi does when
 * `zlib.zstdCompressSync` throws.
 *
 * Divergence: pi's other null case — Node's zlib being absent in browser
 * builds — is not modeled; zstd-jni is an ordinary Android runtime dependency,
 * so a native-library failure is just a compression failure.
 *
 * [Zstd.compress] is a blocking JNI call, but this stays synchronous (not
 * `suspend`): callers run it under an injected IO dispatcher, and making it
 * suspend would force a real-dispatch hop into virtual-time-ordered stream
 * tests without changing the fallback semantics.
 */
fun compressRequestBodyZstd(
    bodyJson: String,
    level: Int = REQUEST_COMPRESSION_ZSTD_LEVEL,
): ByteArray? = try {
    Zstd.compress(bodyJson.toByteArray(Charsets.UTF_8), level)
} catch (_: Throwable) {
    null
}
