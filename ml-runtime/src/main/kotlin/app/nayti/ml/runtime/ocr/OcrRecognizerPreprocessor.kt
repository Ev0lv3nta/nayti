package app.nayti.ml.runtime.ocr

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

object OcrRecognizerContract {
    const val InputName = "x"
    const val OutputName = "fetch_name_0"
    const val InputHeight = 48
    const val InputWidth = 320
    const val MaximumBatchSize = 4
    const val Timesteps = 40
    const val Classes = 519
    const val BlankIndex = 0
    const val SpaceIndex = 518
    const val CharacterCount = 517
    const val DecoderSha256 = "3c7adfef584ce1617fb60c0f89a4c6241f094ddded68f3fc82246c800ca02d51"
}

/** One bounded pixel copy is shared by every recognizer batch for the current decoded image. */
class OcrRecognizerImage private constructor(
    val width: Int,
    val height: Int,
    private var pixels: IntArray?,
) : AutoCloseable {
    fun sampleBilinear(x: Double, y: Double, destination: FloatArray) {
        require(destination.size >= 3)
        val source = checkNotNull(pixels) { "Recognizer image is closed" }
        val boundedX = x.coerceIn(0.0, width - 1.0)
        val boundedY = y.coerceIn(0.0, height - 1.0)
        val left = boundedX.toInt()
        val top = boundedY.toInt()
        val right = minOf(left + 1, width - 1)
        val bottom = minOf(top + 1, height - 1)
        val xWeight = (boundedX - left).toFloat()
        val yWeight = (boundedY - top).toFloat()
        val topLeft = source[top * width + left]
        val topRight = source[top * width + right]
        val bottomLeft = source[bottom * width + left]
        val bottomRight = source[bottom * width + right]
        destination[0] = interpolateChannel(topLeft, topRight, bottomLeft, bottomRight, 0, xWeight, yWeight)
        destination[1] = interpolateChannel(topLeft, topRight, bottomLeft, bottomRight, 8, xWeight, yWeight)
        destination[2] = interpolateChannel(topLeft, topRight, bottomLeft, bottomRight, 16, xWeight, yWeight)
    }

    override fun close() {
        pixels = null
    }

    companion object {
        fun from(bitmap: Bitmap): OcrRecognizerImage {
            require(!bitmap.isRecycled) { "Source bitmap is recycled" }
            require(bitmap.width <= OcrDetectorContract.RetryDecodeLongSide)
            require(bitmap.height <= OcrDetectorContract.RetryDecodeLongSide)
            val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrRecognizerImage(bitmap.width, bitmap.height, pixels)
        }

        private fun interpolateChannel(
            topLeft: Int,
            topRight: Int,
            bottomLeft: Int,
            bottomRight: Int,
            shift: Int,
            xWeight: Float,
            yWeight: Float,
        ): Float {
            val first = ((topLeft ushr shift) and 0xff) * (1.0f - xWeight) +
                ((topRight ushr shift) and 0xff) * xWeight
            val second = ((bottomLeft ushr shift) and 0xff) * (1.0f - xWeight) +
                ((bottomRight ushr shift) and 0xff) * xWeight
            return first * (1.0f - yWeight) + second * yWeight
        }
    }
}

class OcrRecognizerPreprocessor(
    private val bufferPool: OcrTensorBufferPool,
) {
    fun prepare(
        image: OcrRecognizerImage,
        regions: List<DetectedTextRegion>,
    ): PreparedRecognizerBatch {
        require(regions.isNotEmpty() && regions.size <= OcrRecognizerContract.MaximumBatchSize)
        val tensor =
            bufferPool.acquire(
                longArrayOf(
                    regions.size.toLong(),
                    3,
                    OcrRecognizerContract.InputHeight.toLong(),
                    OcrRecognizerContract.InputWidth.toLong(),
                ),
            )
        try {
            val destination = tensor.writableBuffer()
            for (index in 0 until tensor.elements) destination.put(index, 0.0f)
            val widths = IntArray(regions.size)
            regions.forEachIndexed { batchIndex, region ->
                widths[batchIndex] = writeRegion(image, region.quadrilateral, batchIndex, destination)
            }
            return PreparedRecognizerBatch(regions.toList(), widths, tensor)
        } catch (failure: Throwable) {
            tensor.close()
            throw failure
        }
    }

    private fun writeRegion(
        image: OcrRecognizerImage,
        quadrilateral: OcrQuadrilateral,
        batchIndex: Int,
        destination: java.nio.FloatBuffer,
    ): Int {
        val topWidth = quadrilateral.topLeft.distanceTo(quadrilateral.topRight)
        val bottomWidth = quadrilateral.bottomLeft.distanceTo(quadrilateral.bottomRight)
        val leftHeight = quadrilateral.topLeft.distanceTo(quadrilateral.bottomLeft)
        val rightHeight = quadrilateral.topRight.distanceTo(quadrilateral.bottomRight)
        val cropWidth = max(topWidth, bottomWidth)
        val cropHeight = max(leftHeight, rightHeight)
        require(cropWidth >= MinimumRegionSide && cropHeight >= MinimumRegionSide) {
            "OCR region is too small"
        }
        val resizedWidth =
            ceil(OcrRecognizerContract.InputHeight * cropWidth / cropHeight).toInt()
                .coerceIn(1, OcrRecognizerContract.InputWidth)
        val transform = PerspectiveTransform.fromUnitSquare(quadrilateral)
        val sample = FloatArray(3)
        val plane = OcrRecognizerContract.InputHeight * OcrRecognizerContract.InputWidth
        val batchOffset = batchIndex * 3 * plane
        for (y in 0 until OcrRecognizerContract.InputHeight) {
            val vertical = (y + 0.5) / OcrRecognizerContract.InputHeight
            for (x in 0 until resizedWidth) {
                val horizontal = (x + 0.5) / resizedWidth
                val point = transform.map(horizontal, vertical)
                image.sampleBilinear(point.x, point.y, sample)
                val offset = y * OcrRecognizerContract.InputWidth + x
                destination.put(batchOffset + offset, sample[0] / 127.5f - 1.0f)
                destination.put(batchOffset + plane + offset, sample[1] / 127.5f - 1.0f)
                destination.put(batchOffset + 2 * plane + offset, sample[2] / 127.5f - 1.0f)
            }
        }
        return resizedWidth
    }

    private companion object {
        const val MinimumRegionSide = 2.0
    }
}

class PreparedRecognizerBatch internal constructor(
    val regions: List<DetectedTextRegion>,
    validWidths: IntArray,
    val tensor: OcrFloatTensorLease,
) : AutoCloseable {
    val validWidths: IntArray = validWidths.clone()

    override fun close() = tensor.close()
}

internal data class PerspectiveTransform(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double,
    private val g: Double,
    private val h: Double,
) {
    fun map(horizontal: Double, vertical: Double): DoublePointForTransform {
        val denominator = g * horizontal + h * vertical + 1.0
        require(kotlin.math.abs(denominator) > Epsilon) { "Degenerate OCR quadrilateral" }
        return DoublePointForTransform(
            x = (a * horizontal + b * vertical + c) / denominator,
            y = (d * horizontal + e * vertical + f) / denominator,
        )
    }

    companion object {
        fun fromUnitSquare(quad: OcrQuadrilateral): PerspectiveTransform {
            val x0 = quad.topLeft.x.toDouble()
            val y0 = quad.topLeft.y.toDouble()
            val x1 = quad.topRight.x.toDouble()
            val y1 = quad.topRight.y.toDouble()
            val x2 = quad.bottomRight.x.toDouble()
            val y2 = quad.bottomRight.y.toDouble()
            val x3 = quad.bottomLeft.x.toDouble()
            val y3 = quad.bottomLeft.y.toDouble()
            val sumX = x0 - x1 + x2 - x3
            val sumY = y0 - y1 + y2 - y3
            val deltaX1 = x1 - x2
            val deltaX2 = x3 - x2
            val deltaY1 = y1 - y2
            val deltaY2 = y3 - y2
            val determinant = deltaX1 * deltaY2 - deltaX2 * deltaY1
            val perspectiveX: Double
            val perspectiveY: Double
            if (kotlin.math.abs(determinant) <= Epsilon) {
                perspectiveX = 0.0
                perspectiveY = 0.0
            } else {
                perspectiveX = (sumX * deltaY2 - deltaX2 * sumY) / determinant
                perspectiveY = (deltaX1 * sumY - sumX * deltaY1) / determinant
            }
            return PerspectiveTransform(
                a = x1 - x0 + perspectiveX * x1,
                b = x3 - x0 + perspectiveY * x3,
                c = x0,
                d = y1 - y0 + perspectiveX * y1,
                e = y3 - y0 + perspectiveY * y3,
                f = y0,
                g = perspectiveX,
                h = perspectiveY,
            )
        }

        private const val Epsilon = 1e-10
    }
}

internal data class DoublePointForTransform(
    val x: Double,
    val y: Double,
)

private fun OcrPoint.distanceTo(other: OcrPoint): Double =
    hypot((x - other.x).toDouble(), (y - other.y).toDouble())
