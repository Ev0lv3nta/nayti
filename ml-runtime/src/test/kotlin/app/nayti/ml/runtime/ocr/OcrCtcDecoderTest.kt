package app.nayti.ml.runtime.ocr

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrCtcDecoderTest {
    private val tokens =
        List(OcrRecognizerContract.Classes) { index ->
            when (index) {
                0 -> "blank"
                OcrRecognizerContract.SpaceIndex -> " "
                else -> "t$index"
            }
        }
    private val decoder = OcrCtcDecoder(OcrDecoderVocabulary.forTesting(tokens))

    @Test
    fun collapsesConsecutiveClassesAndResetsOnBlank() {
        val output = blankOutput(batchSize = 1)
        setClass(output, batch = 0, timestep = 0, classIndex = 1, probability = 0.9f)
        setClass(output, batch = 0, timestep = 1, classIndex = 1, probability = 0.8f)
        setClass(output, batch = 0, timestep = 3, classIndex = 1, probability = 0.7f)
        setClass(output, batch = 0, timestep = 4, classIndex = 2, probability = 0.6f)

        val decoded = decoder.decode(FloatBuffer.wrap(output), batchSize = 1).single()

        assertEquals("t1t1t2", decoded.rawText)
        assertEquals((0.9f + 0.7f + 0.6f) / 3.0f, decoded.confidence, 1e-6f)
    }

    @Test
    fun decodesIndependentBatchRowsAndValidatesProbabilities() {
        val output = blankOutput(batchSize = 2)
        setClass(output, batch = 0, timestep = 0, classIndex = 3, probability = 0.75f)
        setClass(output, batch = 1, timestep = 0, classIndex = 4, probability = 0.85f)
        val decoded = decoder.decode(FloatBuffer.wrap(output), batchSize = 2)
        assertEquals("t3", decoded[0].rawText)
        assertEquals("t4", decoded[1].rawText)

        output[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            decoder.decode(FloatBuffer.wrap(output), batchSize = 2)
        }
    }

    private fun blankOutput(batchSize: Int): FloatArray {
        val output =
            FloatArray(batchSize * OcrRecognizerContract.Timesteps * OcrRecognizerContract.Classes)
        for (batch in 0 until batchSize) {
            for (time in 0 until OcrRecognizerContract.Timesteps) {
                output[index(batch, time, OcrRecognizerContract.BlankIndex)] = 1.0f
            }
        }
        return output
    }

    private fun setClass(
        output: FloatArray,
        batch: Int,
        timestep: Int,
        classIndex: Int,
        probability: Float,
    ) {
        output[index(batch, timestep, OcrRecognizerContract.BlankIndex)] = 1.0f - probability
        output[index(batch, timestep, classIndex)] = probability
    }

    private fun index(batch: Int, timestep: Int, classIndex: Int): Int =
        (batch * OcrRecognizerContract.Timesteps + timestep) * OcrRecognizerContract.Classes + classIndex
}
