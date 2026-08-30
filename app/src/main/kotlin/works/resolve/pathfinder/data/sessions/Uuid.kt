package works.resolve.pathfinder.data.sessions

import java.security.SecureRandom

/**
 * Generates a time-ordered UUIDv7; faithful port of pi's `uuidv7`
 * (packages/ai/src/utils/uuid.ts).
 *
 * Layout, exactly as upstream: 48-bit big-endian Unix-millisecond timestamp in
 * bytes 0-5, a process-wide monotonic counter spread over bytes 6-10 (seeded
 * from random bytes when the clock advances, incremented on ties, with the
 * timestamp bumped on counter wraparound so ordering never regresses), the
 * version/variant nibbles, and random bytes for the remainder. The resulting
 * canonical lowercase hex string sorts chronologically, which is why pi uses
 * it for session and entry ids.
 *
 * Documented divergences, at the narrowest boundary:
 * - pi reads `crypto.getRandomValues` with a `Math.random` fallback for
 *   environments without WebCrypto; the JVM/Android platform always provides
 *   [SecureRandom], so the fallback branch does not exist here.
 * - pi's module-level `lastTimestamp`/`sequence` state is protected by the
 *   single-threaded JS event loop; here [Uuidv7.next] is `@Synchronized` to
 *   keep the same monotonicity guarantee under Kotlin concurrency.
 * - pi extracts the timestamp bytes with float division on a double; here the
 *   equivalent `Long` shifts are used.
 *
 * The internal `System.currentTimeMillis()` below is a deliberate exception
 * to the "no System.currentTimeMillis in domain code" timing rule: this is a
 * verbatim port of pi's `Date.now()`-based generator, and reading wall time is
 * the function's entire job (it is not minting event timestamps).
 */
fun uuidv7(): String = Uuidv7.next()

private object Uuidv7 {
    private val random = SecureRandom()
    private var lastTimestamp = Long.MIN_VALUE
    private var sequence = 0

    @Synchronized
    fun next(): String {
        val randomBytes = ByteArray(16)
        random.nextBytes(randomBytes)
        val timestamp = System.currentTimeMillis()

        if (timestamp > lastTimestamp) {
            sequence = ((randomBytes[6].toInt() and 0xff) shl 24) or
                ((randomBytes[7].toInt() and 0xff) shl 16) or
                ((randomBytes[8].toInt() and 0xff) shl 8) or
                (randomBytes[9].toInt() and 0xff)
            lastTimestamp = timestamp
        } else {
            // Int bit patterns model pi's uint32: `+` wraps in both, and the
            // zero check detects the wraparound at 0xffffffff.
            sequence += 1
            if (sequence == 0) lastTimestamp++
        }

        val bytes = ByteArray(16)
        bytes[0] = ((lastTimestamp ushr 40) and 0xff).toByte()
        bytes[1] = ((lastTimestamp ushr 32) and 0xff).toByte()
        bytes[2] = ((lastTimestamp ushr 24) and 0xff).toByte()
        bytes[3] = ((lastTimestamp ushr 16) and 0xff).toByte()
        bytes[4] = ((lastTimestamp ushr 8) and 0xff).toByte()
        bytes[5] = (lastTimestamp and 0xff).toByte()
        bytes[6] = (0x70 or ((sequence ushr 28) and 0x0f)).toByte()
        bytes[7] = ((sequence ushr 20) and 0xff).toByte()
        bytes[8] = (0x80 or ((sequence ushr 14) and 0x3f)).toByte()
        bytes[9] = ((sequence ushr 6) and 0xff).toByte()
        bytes[10] = (((sequence and 0x3f) shl 2) or (randomBytes[10].toInt() and 0x03)).toByte()
        bytes[11] = randomBytes[11]
        bytes[12] = randomBytes[12]
        bytes[13] = randomBytes[13]
        bytes[14] = randomBytes[14]
        bytes[15] = randomBytes[15]

        val hex = bytes.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
            hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" +
            hex.substring(20, 32)
    }
}
