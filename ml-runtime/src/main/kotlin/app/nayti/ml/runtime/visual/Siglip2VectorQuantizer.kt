package app.nayti.ml.runtime.visual

import kotlin.math.sqrt

data class Siglip2QuantizedVector(
    val normalized: FloatArray,
    val quantized: ByteArray,
)

object Siglip2VectorQuantizer {
    fun normalizeAndQuantize(embedding: FloatArray): Siglip2QuantizedVector {
        require(embedding.size == Siglip2Contract.EmbeddingDimension)
        var squaredNorm = 0.0
        embedding.forEach { value ->
            require(value.isFinite())
            squaredNorm += value.toDouble() * value.toDouble()
        }
        require(squaredNorm > MinimumSquaredNorm)
        val inverseNorm = 1.0 / sqrt(squaredNorm)
        val normalized = FloatArray(embedding.size) { index -> (embedding[index] * inverseNorm).toFloat() }
        val quantized =
            ByteArray(normalized.size) { index ->
                Math.round(normalized[index] * QuantizedMaximum)
                    .coerceIn(-QuantizedMaximum, QuantizedMaximum)
                    .toByte()
            }
        require(quantized.any { it.toInt() != 0 })
        return Siglip2QuantizedVector(normalized, quantized)
    }

    private const val QuantizedMaximum = 127
    private const val MinimumSquaredNorm = 1e-20
}
