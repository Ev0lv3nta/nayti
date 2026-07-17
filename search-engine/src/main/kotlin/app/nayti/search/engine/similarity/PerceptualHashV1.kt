package app.nayti.search.engine.similarity

import kotlin.math.roundToInt

data class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width in 1..MaximumEdge && height in 1..MaximumEdge)
        require(pixels.size == Math.multiplyExact(width, height))
    }

    private companion object {
        const val MaximumEdge = 8_192
    }
}

/** Canonical DCT perceptual hash. The DC bit is zero; the remaining 63 bits encode low-frequency AC. */
object PerceptualHashV1 {
    const val PipelineVersion = "phash-v1"
    const val ComponentHash = "b88379e5ff4d030a0193e528514079b18d5c0619d4500357381d0b4ec82b656a"
    const val DecodeLongSide = 64
    const val DefaultNearDuplicateDistance = 8

    private const val SampleEdge = 32
    private const val FrequencyEdge = 8
    private const val FractionBits = 16
    private const val FractionOne = 1L shl FractionBits
    private const val CosineScale = 1 shl 14
    private val cosine =
        Array(FrequencyEdge) { frequency ->
            IntArray(SampleEdge) { position ->
                val normalization = if (frequency == 0) 1.0 / StrictMath.sqrt(2.0) else 1.0
                (normalization *
                    StrictMath.cos((2.0 * position + 1.0) * frequency * StrictMath.PI / (2.0 * SampleEdge)) *
                    CosineScale).roundToInt()
            }
        }

    fun compute(image: ArgbImage): Long {
        val samples = resizeLuma(image)
        val coefficients = LongArray(FrequencyEdge * FrequencyEdge)
        var output = 0
        for (verticalFrequency in 0 until FrequencyEdge) {
            for (horizontalFrequency in 0 until FrequencyEdge) {
                var coefficient = 0L
                for (y in 0 until SampleEdge) {
                    val vertical = cosine[verticalFrequency][y].toLong()
                    for (x in 0 until SampleEdge) {
                        coefficient +=
                            samples[y * SampleEdge + x].toLong() *
                                cosine[horizontalFrequency][x] *
                                vertical
                    }
                }
                coefficients[output++] = coefficient
            }
        }
        val orderedAc = coefficients.copyOfRange(1, coefficients.size).sortedArray()
        val median = orderedAc[orderedAc.size / 2]
        var hash = 0L
        coefficients.forEachIndexed { index, coefficient ->
            if (index == 0) return@forEachIndexed
            if (coefficient > median) hash = hash or (1L shl (63 - index))
        }
        return hash
    }

    fun hammingDistance(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)

    private fun resizeLuma(image: ArgbImage): IntArray =
        IntArray(SampleEdge * SampleEdge) { outputIndex ->
            val outputX = outputIndex % SampleEdge
            val outputY = outputIndex / SampleEdge
            val sourceX = sourceCoordinate(outputX, image.width)
            val sourceY = sourceCoordinate(outputY, image.height)
            val x0 = (sourceX ushr FractionBits).toInt()
            val y0 = (sourceY ushr FractionBits).toInt()
            val x1 = minOf(x0 + 1, image.width - 1)
            val y1 = minOf(y0 + 1, image.height - 1)
            val fractionX = sourceX and (FractionOne - 1)
            val fractionY = sourceY and (FractionOne - 1)
            val top =
                luma(image.pixels[y0 * image.width + x0]) * (FractionOne - fractionX) +
                    luma(image.pixels[y0 * image.width + x1]) * fractionX
            val bottom =
                luma(image.pixels[y1 * image.width + x0]) * (FractionOne - fractionX) +
                    luma(image.pixels[y1 * image.width + x1]) * fractionX
            ((top * (FractionOne - fractionY) + bottom * fractionY + (1L shl 31)) ushr 32).toInt()
        }

    private fun sourceCoordinate(output: Int, sourceSize: Int): Long {
        val centered =
            ((2L * output + 1L) * sourceSize * FractionOne) / (2L * SampleEdge) -
                FractionOne / 2L
        return centered.coerceIn(0, (sourceSize - 1L) * FractionOne)
    }

    private fun luma(argb: Int): Long {
        val alpha = argb ushr 24 and 0xff
        val inverseAlpha = 255 - alpha
        val red = (((argb ushr 16 and 0xff) * alpha + 255 * inverseAlpha + 127) / 255)
        val green = (((argb ushr 8 and 0xff) * alpha + 255 * inverseAlpha + 127) / 255)
        val blue = (((argb and 0xff) * alpha + 255 * inverseAlpha + 127) / 255)
        return ((77 * red + 150 * green + 29 * blue + 128) ushr 8).toLong()
    }
}
