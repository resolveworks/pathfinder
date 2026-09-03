package works.resolve.pathfinder.ai.utils

import java.security.SecureRandom

/**
 * Time-ordered UUIDv7: 48-bit big-endian Unix-millisecond timestamp in bytes
 * 0-5, then a 41-bit process-wide monotonic sequence seeded once from random
 * bytes and incremented on every call, then random bytes. The lowercase hex
 * string sorts chronologically, which is why pi uses it for session and
 * entry ids.
 *
 * An explicit [timestampMs] is preserved as-is for follower ids (ids minted
 * to match a leader's ordering) and never participates in the clamp; ordinary
 * calls clamp the effective timestamp forward so it never regresses. The
 * sequence is exhausted after 2^41 calls per process.
 *
 * Divergences from pi:
 * - pi's module-level state is protected by the single-threaded JS event
 *   loop; [Uuidv7.next] is `@Synchronized` to keep the same monotonicity
 *   guarantee under Kotlin concurrency.
 * - pi falls back to `Math.random` when WebCrypto is unavailable;
 *   [SecureRandom] always exists on JVM/Android, so there is no fallback
 *   branch.
 * - pi throws `RangeError` on invalid timestamps; Kotlin's closest
 *   programmer-error exception is [IllegalArgumentException].
 *
 * The `System.currentTimeMillis()` call below deliberately breaks the
 * domain-code timing rule: pi's generator is `Date.now()`-based, and reading
 * wall time is this function's entire job.
 */
fun uuidv7(timestampMs: Long? = null): String = Uuidv7.next(timestampMs)

private object Uuidv7 {
    private const val MAX_TIMESTAMP = 0xffffffffffffL
    private const val MAX_SEQUENCE = (1L shl 41) - 1

    private val random = SecureRandom()
    private var lastOrdinaryTimestamp = -1L
    private var sequence: Long? = null

    @Synchronized
    fun next(timestampMs: Long?): String {
        val requestedTimestamp = timestampMs ?: System.currentTimeMillis()
        require(requestedTimestamp in 0..MAX_TIMESTAMP) {
            "UUIDv7 timestamp must be an integer between 0 and $MAX_TIMESTAMP"
        }
        val effectiveTimestamp = if (timestampMs == null) {
            maxOf(requestedTimestamp, lastOrdinaryTimestamp).also { lastOrdinaryTimestamp = it }
        } else {
            timestampMs
        }

        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val seeded = sequence
        sequence = when {
            seeded == null ->
                ((bytes[1].toLong() and 0xff) shl 32) or
                    ((bytes[2].toLong() and 0xff) shl 24) or
                    ((bytes[3].toLong() and 0xff) shl 16) or
                    ((bytes[4].toLong() and 0xff) shl 8) or
                    (bytes[5].toLong() and 0xff)
            seeded == MAX_SEQUENCE ->
                throw IllegalArgumentException("UUIDv7 generator sequence exhausted")
            else -> seeded + 1
        }
        val seq = sequence!!

        for (index in 5 downTo 0) {
            bytes[index] = ((effectiveTimestamp shr ((5 - index) * 8)) and 0xff).toByte()
        }
        bytes[6] = (0x70L or ((seq shr 37) and 0x0f)).toByte()
        bytes[7] = ((seq shr 29) and 0xff).toByte()
        bytes[8] = (0x80L or ((seq shr 23) and 0x3f)).toByte()
        bytes[9] = ((seq shr 15) and 0xff).toByte()
        bytes[10] = ((seq shr 7) and 0xff).toByte()
        bytes[11] = (((seq and 0x7f) shl 1) or (bytes[11].toLong() and 0x01)).toByte()

        val hex = bytes.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
            hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" +
            hex.substring(20, 32)
    }
}
