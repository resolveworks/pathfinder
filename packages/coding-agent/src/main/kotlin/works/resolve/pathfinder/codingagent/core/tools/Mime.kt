package works.resolve.pathfinder.codingagent.core.tools

/** Number of leading bytes pi reads from a file for MIME sniffing. */
const val IMAGE_TYPE_SNIFF_BYTES = 4100

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

/** Magic-byte sniffing for the image MIME types supported inline. */
fun detectSupportedImageMimeType(buffer: ByteArray): String? {
    if (startsWith(buffer, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))) {
        return if (buffer.size > 3 && (buffer[3].toInt() and 0xff) == 0xf7) null else "image/jpeg"
    }
    if (startsWith(buffer, PNG_SIGNATURE)) {
        return if (isPng(buffer) && !isAnimatedPng(buffer)) "image/png" else null
    }
    if (startsWithAscii(buffer, 0, "GIF")) {
        return "image/gif"
    }
    if (startsWithAscii(buffer, 0, "RIFF") && startsWithAscii(buffer, 8, "WEBP")) {
        return "image/webp"
    }
    if (startsWithAscii(buffer, 0, "BM") && isBmp(buffer)) {
        return "image/bmp"
    }
    return null
}

private fun isPng(buffer: ByteArray): Boolean =
    buffer.size >= 16 && readUint32BE(buffer, PNG_SIGNATURE.size) == 13L &&
        startsWithAscii(buffer, 12, "IHDR")

private fun isAnimatedPng(buffer: ByteArray): Boolean {
    var offset: Long = PNG_SIGNATURE.size.toLong()
    while (offset + 8 <= buffer.size) {
        val chunkLength = readUint32BE(buffer, offset.toInt())
        val chunkTypeOffset = offset + 4
        if (startsWithAscii(buffer, chunkTypeOffset.toInt(), "acTL")) return true
        if (startsWithAscii(buffer, chunkTypeOffset.toInt(), "IDAT")) return false

        val nextOffset = offset + 8 + chunkLength + 4
        if (nextOffset <= offset || nextOffset > buffer.size) return false
        offset = nextOffset
    }
    return false
}

private fun isBmp(buffer: ByteArray): Boolean {
    if (buffer.size < 26) return false

    val declaredFileSize = readUint32LE(buffer, 2)
    val pixelDataOffset = readUint32LE(buffer, 10)
    val dibHeaderSize = readUint32LE(buffer, 14)
    if (declaredFileSize != 0L && declaredFileSize < 26) return false
    if (pixelDataOffset < 14L + dibHeaderSize) return false
    if (declaredFileSize != 0L && pixelDataOffset >= declaredFileSize) return false

    val colorPlanes: Int
    val bitsPerPixel: Int
    if (dibHeaderSize == 12L) {
        colorPlanes = readUint16LE(buffer, 22)
        bitsPerPixel = readUint16LE(buffer, 24)
    } else if (dibHeaderSize in 40..124) {
        if (buffer.size < 30) return false
        colorPlanes = readUint16LE(buffer, 26)
        bitsPerPixel = readUint16LE(buffer, 28)
    } else {
        return false
    }

    return colorPlanes == 1 && bitsPerPixel in intArrayOf(1, 4, 8, 16, 24, 32)
}

private fun readUint16LE(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xff)) or ((buffer[offset + 1].toInt() and 0xff) shl 8)

private fun readUint32BE(buffer: ByteArray, offset: Int): Long =
    ((buffer[offset].toInt() and 0xff).toLong() shl 24) or
        ((buffer[offset + 1].toInt() and 0xff).toLong() shl 16) or
        ((buffer[offset + 2].toInt() and 0xff).toLong() shl 8) or
        (buffer[offset + 3].toInt() and 0xff).toLong()

private fun readUint32LE(buffer: ByteArray, offset: Int): Long =
    ((buffer[offset].toInt() and 0xff).toLong()) or
        ((buffer[offset + 1].toInt() and 0xff).toLong() shl 8) or
        ((buffer[offset + 2].toInt() and 0xff).toLong() shl 16) or
        ((buffer[offset + 3].toInt() and 0xff).toLong() shl 24)

private fun startsWith(buffer: ByteArray, bytes: ByteArray): Boolean {
    if (buffer.size < bytes.size) return false
    for (index in bytes.indices) {
        if (buffer[index] != bytes[index]) return false
    }
    return true
}

private fun startsWithAscii(buffer: ByteArray, offset: Int, text: String): Boolean {
    if (buffer.size < offset + text.length) return false
    for (index in text.indices) {
        if (buffer[offset + index].toInt() != text[index].code) return false
    }
    return true
}
