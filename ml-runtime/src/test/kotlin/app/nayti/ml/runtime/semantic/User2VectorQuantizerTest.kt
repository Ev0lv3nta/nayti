package app.nayti.ml.runtime.semantic

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class User2VectorQuantizerTest {
    @Test
    fun normalizesAndUsesSymmetricFixedQint8Contract() {
        val embedding = FloatArray(User2Contract.EmbeddingDimension)
        embedding[0] = 3f
        embedding[1] = 4f

        val result = User2VectorQuantizer.normalizeAndQuantize(embedding)

        assertTrue(abs(result.normalized[0] - 0.6f) < 1e-6f)
        assertTrue(abs(result.normalized[1] - 0.8f) < 1e-6f)
        assertEquals(76, result.quantized[0].toInt())
        assertEquals(102, result.quantized[1].toInt())
        assertEquals(User2Contract.EmbeddingDimension, result.quantized.size)
    }

    @Test
    fun positiveScaleDoesNotChangeStoredVector() {
        val baseline = FloatArray(User2Contract.EmbeddingDimension) { index -> (index - 190).toFloat() }
        val scaled = FloatArray(User2Contract.EmbeddingDimension) { index -> baseline[index] * 17f }

        assertArrayEquals(
            User2VectorQuantizer.normalizeAndQuantize(baseline).quantized,
            User2VectorQuantizer.normalizeAndQuantize(scaled).quantized,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroVector() {
        User2VectorQuantizer.normalizeAndQuantize(FloatArray(User2Contract.EmbeddingDimension))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteVector() {
        User2VectorQuantizer.normalizeAndQuantize(
            FloatArray(User2Contract.EmbeddingDimension) { index -> if (index == 0) Float.NaN else 1f },
        )
    }
}
