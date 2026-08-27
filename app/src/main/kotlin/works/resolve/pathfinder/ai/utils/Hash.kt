package works.resolve.pathfinder.ai.utils

/**
 * Fast deterministic hash to shorten long strings, ported verbatim from pi's
 * shortHash (packages/ai/src/utils/hash.ts).
 *
 * Math.imul-equivalent 32-bit multiply with 2654435761=0x9E3779B1,
 * 1597334677=0x5F356495, 2246822507=0x85EBCA6B, 3266489909=0xC2B2AE35;
 * avalanche; unsigned base36, h2 then h1.
 */
internal fun shortHash(str: String): String {
    var h1 = 0xdeadbeef.toInt()
    var h2 = 0x41c6ce57.toInt()
    for (ch in str) {
        val c = ch.code
        h1 = ((h1 xor c) * 0x9E3779B1.toInt()).toInt()
        h2 = ((h2 xor c) * 0x5F356495.toInt()).toInt()
    }
    h1 = (h1 xor (h1 ushr 16)) * 0x85EBCA6B.toInt() xor ((h2 xor (h2 ushr 13)) * 0xC2B2AE35.toInt())
    h2 = (h2 xor (h2 ushr 16)) * 0x85EBCA6B.toInt() xor ((h1 xor (h1 ushr 13)) * 0xC2B2AE35.toInt())
    return (h2.toLong() and 0xFFFFFFFFL).toString(36) +
        (h1.toLong() and 0xFFFFFFFFL).toString(36)
}
