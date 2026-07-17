package app.nayti.ml.runtime.visual

import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Siglip2VectorQuantizerTest {
    @Test
    fun normalizesAndQuantizesWithScaleInvariantContract() {
        val embedding = FloatArray(Siglip2Contract.EmbeddingDimension)
        embedding[0] = 3f
        embedding[1] = 4f

        val first = Siglip2VectorQuantizer.normalizeAndQuantize(embedding)
        val scaled = Siglip2VectorQuantizer.normalizeAndQuantize(FloatArray(embedding.size) { embedding[it] * 7f })

        assertEquals(1.0, sqrt(first.normalized.sumOf { it.toDouble() * it }), 1e-6)
        assertEquals(0.6f, first.normalized[0], 1e-6f)
        assertEquals(0.8f, first.normalized[1], 1e-6f)
        assertArrayEquals(first.quantized, scaled.quantized)
    }

    @Test
    fun rejectsZeroAndNonFiniteEmbeddings() {
        assertThrows(IllegalArgumentException::class.java) {
            Siglip2VectorQuantizer.normalizeAndQuantize(FloatArray(Siglip2Contract.EmbeddingDimension))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Siglip2VectorQuantizer.normalizeAndQuantize(
                FloatArray(Siglip2Contract.EmbeddingDimension).also { it[0] = Float.NaN },
            )
        }
    }
}
