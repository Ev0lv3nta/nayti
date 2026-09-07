package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantizedDotScoreTest {
    @Test
    fun unitBasisVectorIsNotReportedAsCosineProbability() {
        assertEquals(1302, QuantizedDotScore.scaledMicros(127 * 127, 768))
        assertEquals(2604, QuantizedDotScore.scaledMicros(127 * 127, 384))
        assertEquals(-1302, QuantizedDotScore.scaledMicros(-127 * 127, 768))
        assertEquals(0, QuantizedDotScore.scaledMicros(0, 768))
    }

    @Test
    fun coordinateBoundIsScaledWithoutIntegerOverflow() {
        assertEquals(1_000_000, QuantizedDotScore.scaledMicros(768 * 127 * 127, 768))
        assertEquals(-1_000_000, QuantizedDotScore.scaledMicros(-768 * 127 * 127, 768))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDimension() {
        QuantizedDotScore.scaledMicros(0, 0)
    }
}
