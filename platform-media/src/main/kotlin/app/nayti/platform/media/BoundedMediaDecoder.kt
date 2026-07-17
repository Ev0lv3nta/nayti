package app.nayti.platform.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class MediaDecodeProbe(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val allocationBytes: Int,
)

/** Owns a bounded software bitmap and must be closed after the consumer has copied its pixels. */
class DecodedMediaImage internal constructor(
    private val ownedBitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val bitmap: Bitmap
        get() {
            check(!closed.get()) { "Decoded media image is already closed" }
            return ownedBitmap
        }

    val decodedWidth: Int = ownedBitmap.width
    val decodedHeight: Int = ownedBitmap.height
    val allocationBytes: Int = ownedBitmap.allocationByteCount

    override fun close() {
        if (closed.compareAndSet(false, true)) ownedBitmap.recycle()
    }
}

class BoundedMediaDecoder(
    private val resolver: ContentResolver,
    private val mediaStore: MediaStoreGateway,
) {
    fun probe(key: MediaKey, maxEdge: Int = DefaultMaxEdge): MediaDecodeProbe {
        return decode(key, maxEdge).use { decoded ->
            MediaDecodeProbe(
                sourceWidth = decoded.sourceWidth,
                sourceHeight = decoded.sourceHeight,
                decodedWidth = decoded.decodedWidth,
                decodedHeight = decoded.decodedHeight,
                allocationBytes = decoded.allocationBytes,
            )
        }
    }

    fun decode(key: MediaKey, maxEdge: Int = MaximumAllowedEdge): DecodedMediaImage {
        require(maxEdge in 64..MaximumAllowedEdge)
        val uri = mediaStore.contentUri(key)
        try {
            var sourceWidth = 0
            var sourceHeight = 0
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    sourceWidth = info.size.width
                    sourceHeight = info.size.height
                    check(sourceWidth > 0 && sourceHeight > 0) {
                        "Decoder reported invalid source bounds: ${sourceWidth}x${sourceHeight}"
                    }
                    val longest = max(sourceWidth, sourceHeight)
                    val sampleSize = max(1, (longest + maxEdge - 1) / maxEdge)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setTargetSampleSize(sampleSize)
                    decoder.setOnPartialImageListener { false }
                }
            if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
                bitmap.recycle()
                error("Decoder exceeded bounded edge: ${bitmap.width}x${bitmap.height}")
            }
            return DecodedMediaImage(bitmap, sourceWidth, sourceHeight)
        } catch (failure: SecurityException) {
            throw MediaDecodeAccessException(key, failure)
        } catch (failure: FileNotFoundException) {
            throw MediaDecodeAccessException(key, failure)
        } catch (failure: ImageDecoder.DecodeException) {
            throw MediaDecodeContentException(key, failure.error, failure)
        } catch (failure: IOException) {
            throw MediaDecodeIoException(key, failure)
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

class MediaDecodeContentException(
    val key: MediaKey,
    val decoderError: Int,
    cause: Throwable,
) : Exception("Media content cannot be decoded for ${key.volumeName}:${key.mediaStoreId}", cause)

class MediaDecodeIoException(
    val key: MediaKey,
    cause: Throwable,
) : Exception("Media I/O failed while decoding ${key.volumeName}:${key.mediaStoreId}", cause)
