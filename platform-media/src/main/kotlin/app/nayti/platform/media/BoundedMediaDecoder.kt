package app.nayti.platform.media

import android.content.ContentResolver
import android.graphics.ImageDecoder
import java.io.FileNotFoundException
import kotlin.math.max

data class MediaDecodeProbe(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val allocationBytes: Int,
)

class BoundedMediaDecoder(
    private val resolver: ContentResolver,
    private val mediaStore: MediaStoreGateway,
) {
    fun probe(key: MediaKey, maxEdge: Int = DefaultMaxEdge): MediaDecodeProbe {
        require(maxEdge in 64..MaximumAllowedEdge)
        val uri = mediaStore.contentUri(key)
        try {
            var sourceWidth = 0
            var sourceHeight = 0
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    sourceWidth = info.size.width
                    sourceHeight = info.size.height
                    val longest = max(sourceWidth, sourceHeight)
                    val sampleSize = max(1, (longest + maxEdge - 1) / maxEdge)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    decoder.setTargetSampleSize(sampleSize)
                    decoder.setOnPartialImageListener { false }
                }
            return try {
                check(bitmap.width <= maxEdge && bitmap.height <= maxEdge) {
                    "Decoder exceeded bounded edge: ${bitmap.width}x${bitmap.height}"
                }
                MediaDecodeProbe(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    decodedWidth = bitmap.width,
                    decodedHeight = bitmap.height,
                    allocationBytes = bitmap.allocationByteCount,
                )
            } finally {
                bitmap.recycle()
            }
        } catch (failure: SecurityException) {
            throw MediaDecodeAccessException(key, failure)
        } catch (failure: FileNotFoundException) {
            throw MediaDecodeAccessException(key, failure)
        }
    }

    private companion object {
        const val DefaultMaxEdge = 512
        const val MaximumAllowedEdge = 2_048
    }
}

class MediaDecodeAccessException(
    val key: MediaKey,
    cause: Throwable,
) : Exception("Media access changed while decoding ${key.volumeName}:${key.mediaStoreId}", cause)
