package app.nayti.ml.runtime.visual

import android.graphics.Bitmap

object Siglip2ImagePreprocessor {
    fun preprocess(bitmap: Bitmap): FloatArray {
        require(!bitmap.isRecycled)
        require(bitmap.width in 1..Siglip2Contract.MaximumDecodedEdge)
        require(bitmap.height in 1..Siglip2Contract.MaximumDecodedEdge)
        val source = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
        bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val pixels =
            if (bitmap.width == Siglip2Contract.ImageEdge && bitmap.height == Siglip2Contract.ImageEdge) {
                source
            } else {
                resizePillowBilinear(source, bitmap.width, bitmap.height, Siglip2Contract.ImageEdge)
            }
        return normalizeRgbToNchw(pixels)
    }

    internal fun normalizeRgbToNchw(pixels: IntArray): FloatArray {
        require(pixels.size == Siglip2Contract.ImageEdge * Siglip2Contract.ImageEdge)
        val plane = pixels.size
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, argb ->
                tensor[index] = normalize(argb ushr 16 and 0xff)
                tensor[plane + index] = normalize(argb ushr 8 and 0xff)
                tensor[2 * plane + index] = normalize(argb and 0xff)
            }
        }
    }

    private fun normalize(channel: Int): Float = channel / 127.5f - 1.0f

    /** Matches Pillow's separable BILINEAR coordinates, downscale support and per-pass byte rounding. */
    internal fun resizePillowBilinear(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetEdge: Int,
    ): IntArray {
        require(sourceWidth > 0 && sourceHeight > 0 && targetEdge > 0)
        require(source.size == Math.multiplyExact(sourceWidth, sourceHeight))
        val horizontalWeights = coefficients(sourceWidth, targetEdge)
        val verticalWeights = coefficients(sourceHeight, targetEdge)
        val horizontal = IntArray(Math.multiplyExact(targetEdge, sourceHeight))
        for (y in 0 until sourceHeight) {
            for (x in 0 until targetEdge) {
                horizontal[y * targetEdge + x] = sampleRow(source, y * sourceWidth, horizontalWeights[x])
            }
        }
        return IntArray(targetEdge * targetEdge) { index ->
            val x = index % targetEdge
            val y = index / targetEdge
            sampleColumn(horizontal, x, targetEdge, verticalWeights[y])
        }
    }

    private fun coefficients(sourceSize: Int, targetSize: Int): Array<Weights> {
        val scale = sourceSize.toDouble() / targetSize
        val filterScale = maxOf(scale, 1.0)
        val support = filterScale
        return Array(targetSize) { output ->
            val center = (output + 0.5) * scale
            val first = maxOf(0, (center - support + 0.5).toInt())
            val lastExclusive = minOf(sourceSize, (center + support + 0.5).toInt())
            val values = DoubleArray(lastExclusive - first) { offset ->
                maxOf(0.0, 1.0 - kotlin.math.abs((first + offset + 0.5 - center) / filterScale))
            }
            val total = values.sum()
            require(total > 0.0)
            Weights(first, DoubleArray(values.size) { values[it] / total })
        }
    }

    private fun sampleRow(source: IntArray, rowOffset: Int, weights: Weights): Int =
        sample(weights) { offset -> source[rowOffset + weights.first + offset] }

    private fun sampleColumn(source: IntArray, x: Int, stride: Int, weights: Weights): Int =
        sample(weights) { offset -> source[(weights.first + offset) * stride + x] }

    private inline fun sample(weights: Weights, pixel: (Int) -> Int): Int {
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        weights.values.forEachIndexed { offset, weight ->
            val argb = pixel(offset)
            red += (argb ushr 16 and 0xff) * weight
            green += (argb ushr 8 and 0xff) * weight
            blue += (argb and 0xff) * weight
        }
        return 0xff000000.toInt() or
            (roundByte(red) shl 16) or
            (roundByte(green) shl 8) or
            roundByte(blue)
    }

    private fun roundByte(value: Double): Int = (value + 0.5).toInt().coerceIn(0, 255)

    private data class Weights(
        val first: Int,
        val values: DoubleArray,
    )
}
