package works.resolve.pathfinder.ssh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.codingagent.core.tools.DecodedImage
import works.resolve.pathfinder.codingagent.core.tools.ImageProcessing
import works.resolve.pathfinder.codingagent.core.tools.ResizeEncodings

/**
 * [ImageProcessing] via `android.graphics.Bitmap`, the Android counterpart
 * of pi's Photon codec. EXIF orientation is applied on decode (the Bitmap
 * factory ignores it), so [probe] reports oriented dimensions as the ported
 * decision logic expects. Scaling uses the platform filter (bilinear
 * filtering), not upstream's Lanczos3: an acceptable platform-mechanics
 * substitution at this seam.
 */
class BitmapImageProcessing : ImageProcessing {

    override suspend fun probe(bytes: ByteArray, mimeType: String): DecodedImage? =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext null
            }
            val (width, height) =
                if (exifOrientation(bytes) in SWAP_ORIENTATIONS) {
                    bounds.outHeight to bounds.outWidth
                } else {
                    bounds.outWidth to bounds.outHeight
                }
            DecodedImage(width, height)
        }

    override suspend fun convertToPng(bytes: ByteArray): ByteArray? =
        withContext(Dispatchers.Default) {
            val bitmap = decodeOriented(bytes) ?: return@withContext null
            encode(bitmap, Bitmap.CompressFormat.PNG, 100)
        }

    override suspend fun resizeEncode(
        bytes: ByteArray,
        width: Int,
        height: Int,
        jpegQualities: List<Int>
    ): ResizeEncodings? = withContext(Dispatchers.Default) {
        val original = decodeOriented(bytes) ?: return@withContext null
        val resized =
            if (original.width == width && original.height == height) {
                original
            } else {
                Bitmap.createScaledBitmap(original, width, height, true)
            }
        val png = encode(resized, Bitmap.CompressFormat.PNG, 100)
        val jpegByQuality =
            jpegQualities.mapNotNull { quality ->
                encode(resized, Bitmap.CompressFormat.JPEG, quality)?.let { quality to it }
            }.toMap()
        if (png == null && jpegByQuality.isEmpty()) {
            null
        } else {
            ResizeEncodings(png ?: ByteArray(0), jpegByQuality)
        }
    }

    private fun decodeOriented(bytes: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return when (val orientation = exifOrientation(bytes)) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> bitmap

            else -> {
                val matrix = Matrix()
                val rotated = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)

                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)

                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)

                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)

                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)

                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.postRotate(90f)
                        matrix.postScale(-1f, 1f)
                    }

                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.postRotate(-90f)
                        matrix.postScale(-1f, 1f)
                    }

                    else -> false
                }
                if (rotated) {
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
            }
        }
    }

    private fun exifOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, output)) output.toByteArray() else null
    }

    private companion object {
        val SWAP_ORIENTATIONS =
            setOf(
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE
            )
    }
}
