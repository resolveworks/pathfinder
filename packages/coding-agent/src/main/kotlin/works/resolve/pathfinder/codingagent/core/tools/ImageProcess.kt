package works.resolve.pathfinder.codingagent.core.tools

import java.util.Base64

/**
 * Platform codec seam for the read tool's image path. The decision logic
 * around it (supported-MIME normalization, the 2000×2000/4.5MB resize
 * decision, candidate order, notes and hints) is ported from pi; decode,
 * EXIF-orientation application, resize (Lanczos3), and encode are platform
 * mechanics supplied here.
 *
 * Every member returning null models pi's "Photon unavailable/failed"
 * outcomes; any thrown error is caught and treated the same way. Unlike
 * upstream's worker pool, [resizeEncode] is called once per dimension step and
 * must resize from the original bytes each time.
 */
interface ImageProcessing {
    /** Decoded dimensions after EXIF orientation; null when undecodable. */
    suspend fun probe(bytes: ByteArray, mimeType: String): DecodedImage?

    /** Re-encode arbitrary image bytes as PNG; null when unsupported. */
    suspend fun convertToPng(bytes: ByteArray): ByteArray?

    /** Resize to exactly [width]×[height] and return the candidate encodings. */
    suspend fun resizeEncode(
        bytes: ByteArray,
        width: Int,
        height: Int,
        jpegQualities: List<Int>
    ): ResizeEncodings?
}

class DecodedImage(val width: Int, val height: Int)

class ResizeEncodings(val png: ByteArray, val jpegByQuality: Map<Int, ByteArray>)

class ImageResizeOptions(
    val maxWidth: Int = 2000,
    val maxHeight: Int = 2000,
    /** 4.5MB of base64 payload (below Anthropic's 5MB limit). */
    val maxBytes: Double = 4.5 * 1024 * 1024,
    val jpegQuality: Int = 80
)

class ResizedImage(
    /** base64 */
    val data: String,
    val mimeType: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val width: Int,
    val height: Int,
    val wasResized: Boolean
)

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * Resize an image to fit within the specified max dimensions and encoded
 * file size. Returns null if the image cannot be resized below maxBytes.
 *
 * Strategy for staying under maxBytes:
 * 1. First resize to maxWidth/maxHeight
 * 2. Try both PNG and JPEG formats, pick the smaller one
 * 3. If still too large, try JPEG with decreasing quality
 * 4. If still too large, progressively reduce dimensions until 1x1
 */
suspend fun resizeImage(
    codec: ImageProcessing?,
    inputBytes: ByteArray,
    mimeType: String,
    options: ImageResizeOptions?
): ResizedImage? {
    if (codec == null) {
        return null
    }
    return try {
        resizeImageUnchecked(codec, inputBytes, mimeType, options ?: ImageResizeOptions())
    } catch (_: Throwable) {
        null
    }
}

private suspend fun resizeImageUnchecked(
    codec: ImageProcessing,
    inputBytes: ByteArray,
    mimeType: String,
    opts: ImageResizeOptions
): ResizedImage? {
    val inputBase64Size = Math.ceil(inputBytes.size / 3.0).toInt() * 4

    val image = codec.probe(inputBytes, mimeType) ?: return null
    val originalWidth = image.width
    val originalHeight = image.height
    val format = mimeType.split("/").getOrNull(1) ?: "png"

    // Check if already within all limits (dimensions AND encoded size)
    if (originalWidth <= opts.maxWidth && originalHeight <= opts.maxHeight &&
        inputBase64Size < opts.maxBytes
    ) {
        return ResizedImage(
            data = base64(inputBytes),
            mimeType = mimeType.ifEmpty { "image/$format" },
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            width = originalWidth,
            height = originalHeight,
            wasResized = false
        )
    }

    // Calculate initial dimensions respecting max limits
    var targetWidth = originalWidth
    var targetHeight = originalHeight

    if (targetWidth > opts.maxWidth) {
        targetHeight = Math.round(targetHeight * opts.maxWidth.toDouble() / targetWidth).toInt()
        targetWidth = opts.maxWidth
    }
    if (targetHeight > opts.maxHeight) {
        targetWidth = Math.round(targetWidth * opts.maxHeight.toDouble() / targetHeight).toInt()
        targetHeight = opts.maxHeight
    }

    val qualitySteps = linkedSetOf(opts.jpegQuality, 85, 70, 55, 40).toList()
    var currentWidth = targetWidth
    var currentHeight = targetHeight

    while (true) {
        val encodings = codec.resizeEncode(inputBytes, currentWidth, currentHeight, qualitySteps)
        if (encodings != null) {
            val candidates = buildList {
                add("image/png" to encodings.png)
                for (quality in qualitySteps) {
                    encodings.jpegByQuality[quality]?.let { add("image/jpeg" to it) }
                }
            }
            for ((candidateMime, bytes) in candidates) {
                val data = base64(bytes)
                if (data.length < opts.maxBytes) {
                    return ResizedImage(
                        data = data,
                        mimeType = candidateMime,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        width = currentWidth,
                        height = currentHeight,
                        wasResized = true
                    )
                }
            }
        }

        if (currentWidth == 1 && currentHeight == 1) {
            break
        }

        val nextWidth = if (currentWidth == 1) 1 else maxOf(1, (currentWidth * 0.75).toInt())
        val nextHeight = if (currentHeight == 1) 1 else maxOf(1, (currentHeight * 0.75).toInt())
        if (nextWidth == currentWidth && nextHeight == currentHeight) {
            break
        }

        currentWidth = nextWidth
        currentHeight = nextHeight
    }

    return null
}

/**
 * Format a dimension note for resized images. This helps the model understand
 * the coordinate mapping.
 */
fun formatDimensionNote(result: ResizedImage): String? {
    if (!result.wasResized) {
        return null
    }

    val scale = result.originalWidth.toDouble() / result.width
    return "[Image: original ${result.originalWidth}x${result.originalHeight}, displayed at "
    "${result.width}x${result.height}. " +
        "Multiply coordinates by ${"%.2f".format(
            java.util.Locale.ROOT,
            scale
        )} to map to original image.]"
}

private class NormalizedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val convertedFrom: String?
)

private fun baseMimeType(mimeType: String): String =
    mimeType.split(";").firstOrNull()?.trim()?.lowercase() ?: mimeType.lowercase()

private fun normalizeSupportedImageMimeType(mimeType: String): String? =
    when (baseMimeType(mimeType)) {
        "image/png" -> "image/png"
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/gif" -> "image/gif"
        "image/webp" -> "image/webp"
        else -> null
    }

private suspend fun normalizeImage(
    codec: ImageProcessing?,
    bytes: ByteArray,
    mimeType: String
): NormalizedImage? {
    val normalizedMimeType = normalizeSupportedImageMimeType(mimeType)
    if (normalizedMimeType != null) {
        return NormalizedImage(bytes, normalizedMimeType, convertedFrom = null)
    }

    val pngBytes = codec?.convertToPng(bytes) ?: return null

    return NormalizedImage(pngBytes, "image/png", convertedFrom = baseMimeType(mimeType))
}

private fun conversionHint(from: String?, to: String): String? {
    if (from == null || from == to) return null
    return "[Image converted from $from to $to.]"
}

sealed class ProcessImageResult {
    class Ok(val data: String, val mimeType: String, val hints: List<String>) : ProcessImageResult()

    class Failed(val message: String) : ProcessImageResult()
}

suspend fun processImage(
    codec: ImageProcessing?,
    bytes: ByteArray,
    mimeType: String,
    autoResizeImages: Boolean,
    resizeOptions: ImageResizeOptions? = null
): ProcessImageResult {
    val normalized = normalizeImage(codec, bytes, mimeType)
        ?: return ProcessImageResult.Failed(
            "[Image omitted: could not be converted to a supported inline image format.]"
        )

    if (autoResizeImages) {
        val resized = resizeImage(codec, normalized.bytes, normalized.mimeType, resizeOptions)
            ?: return ProcessImageResult.Failed(
                "[Image omitted: could not be resized below the inline image size limit.]"
            )

        val hints = mutableListOf<String>()
        conversionHint(normalized.convertedFrom, resized.mimeType)?.let { hints.add(it) }
        formatDimensionNote(resized)?.let { hints.add(it) }

        return ProcessImageResult.Ok(resized.data, resized.mimeType, hints)
    }

    val hints = mutableListOf<String>()
    conversionHint(normalized.convertedFrom, normalized.mimeType)?.let { hints.add(it) }

    return ProcessImageResult.Ok(base64(normalized.bytes), normalized.mimeType, hints)
}
