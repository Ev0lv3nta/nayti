package app.nayti.ml.runtime.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class DetectorResizePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val tensorWidth: Int,
    val tensorHeight: Int,
) {
    val widthScale: Float = tensorWidth.toFloat() / sourceWidth
    val heightScale: Float = tensorHeight.toFloat() / sourceHeight

    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(tensorWidth >= 32 && tensorHeight >= 32)
        require(tensorWidth % 32 == 0 && tensorHeight % 32 == 0)
        require(tensorWidth <= OcrDetectorContract.MaximumModelSide)
        require(tensorHeight <= OcrDetectorContract.MaximumModelSide)
        require(tensorWidth.toLong() * tensorHeight <= OcrDetectorContract.MaximumTensorPixels)
    }
}

object OcrDetectorContract {
    const val InputName = "x"
    const val OutputName = "fetch_name_0"
    const val BaseDecodeLongSide = 1_280
    const val RetryDecodeLongSide = 1_920
    const val MinimumModelSide = 736
    const val MaximumModelSide = 4_000
    const val DimensionMultiple = 32
    const val MaximumTensorPixels = RetryDecodeLongSide * RetryDecodeLongSide
    const val ProbabilityThreshold = 0.20f
    const val BoxThreshold = 0.45f
    const val MaximumCandidates = 3_000
    const val UnclipRatio = 1.40f

    private val Mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val StandardDeviation = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun normalizedBgr(argb: Int, destination: FloatArray = FloatArray(3)): FloatArray {
        require(destination.size >= 3)
        val blue = (argb and 0xff) / 255.0f
        val green = ((argb ushr 8) and 0xff) / 255.0f
        val red = ((argb ushr 16) and 0xff) / 255.0f
        destination[0] = (blue - Mean[0]) / StandardDeviation[0]
        destination[1] = (green - Mean[1]) / StandardDeviation[1]
        destination[2] = (red - Mean[2]) / StandardDeviation[2]
        return destination
    }
}

object OcrDetectorResizePlanner {
    fun plan(sourceWidth: Int, sourceHeight: Int): DetectorResizePlan {
        require(sourceWidth in 1..OcrDetectorContract.RetryDecodeLongSide)
        require(sourceHeight in 1..OcrDetectorContract.RetryDecodeLongSide)

        var ratio =
            if (min(sourceWidth, sourceHeight) < OcrDetectorContract.MinimumModelSide) {
                OcrDetectorContract.MinimumModelSide.toDouble() / min(sourceWidth, sourceHeight)
            } else {
                1.0
            }
        var targetWidth = (sourceWidth * ratio).toInt()
        var targetHeight = (sourceHeight * ratio).toInt()
        if (max(targetWidth, targetHeight) > OcrDetectorContract.MaximumModelSide) {
            ratio = OcrDetectorContract.MaximumModelSide.toDouble() / max(targetWidth, targetHeight)
            targetWidth = (targetWidth * ratio).toInt()
            targetHeight = (targetHeight * ratio).toInt()
        }
        targetWidth = targetWidth.roundToModelMultiple()
        targetHeight = targetHeight.roundToModelMultiple()
        return DetectorResizePlan(sourceWidth, sourceHeight, targetWidth, targetHeight)
    }

    private fun Int.roundToModelMultiple(): Int =
        max(
            round(toDouble() / OcrDetectorContract.DimensionMultiple).toInt() *
                OcrDetectorContract.DimensionMultiple,
            OcrDetectorContract.DimensionMultiple,
        )
}

class OcrDetectorPreprocessor(
    private val bufferPool: OcrTensorBufferPool,
) {
    fun prepare(source: Bitmap): PreparedDetectorInput {
        require(!source.isRecycled) { "Source bitmap is recycled" }
        val plan = OcrDetectorResizePlanner.plan(source.width, source.height)
        val tensor =
            bufferPool.acquire(
                longArrayOf(1, 3, plan.tensorHeight.toLong(), plan.tensorWidth.toLong()),
            )
        try {
            writeTensor(source, plan, tensor)
            return PreparedDetectorInput(plan, tensor)
        } catch (failure: Throwable) {
            tensor.close()
            throw failure
        }
    }

    private fun writeTensor(
        source: Bitmap,
        plan: DetectorResizePlan,
        tensor: OcrFloatTensorLease,
    ) {
        val scaled =
            if (source.width == plan.tensorWidth && source.height == plan.tensorHeight) {
                source
            } else {
                Bitmap.createScaledBitmap(source, plan.tensorWidth, plan.tensorHeight, true)
            }
        try {
            val destination = tensor.writableBuffer()
            val planeSize = Math.multiplyExact(plan.tensorWidth, plan.tensorHeight)
            val row = IntArray(plan.tensorWidth)
            val channels = FloatArray(3)
            for (y in 0 until plan.tensorHeight) {
                scaled.getPixels(row, 0, plan.tensorWidth, 0, y, plan.tensorWidth, 1)
                val rowOffset = y * plan.tensorWidth
                for (x in row.indices) {
                    OcrDetectorContract.normalizedBgr(row[x], channels)
                    val offset = rowOffset + x
                    destination.put(offset, channels[0])
                    destination.put(planeSize + offset, channels[1])
                    destination.put(2 * planeSize + offset, channels[2])
                }
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}

class PreparedDetectorInput internal constructor(
    val resize: DetectorResizePlan,
    val tensor: OcrFloatTensorLease,
) : AutoCloseable {
    override fun close() = tensor.close()
}
