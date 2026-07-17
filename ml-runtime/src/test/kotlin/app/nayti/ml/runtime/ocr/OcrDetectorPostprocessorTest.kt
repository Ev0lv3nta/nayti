package app.nayti.ml.runtime.ocr

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDetectorPostprocessorTest {
    private val resize = DetectorResizePlan(64, 64, 64, 64)

    @Test
    fun extractsScoresExpandsAndOrdersTextRegions() {
        val map = FloatArray(64 * 64)
        fill(map, left = 8, top = 8, rightExclusive = 28, bottomExclusive = 16, probability = 0.90f)
        fill(map, left = 34, top = 40, rightExclusive = 58, bottomExclusive = 49, probability = 0.80f)

        val regions = OcrDetectorPostprocessor().process(FloatBuffer.wrap(map), 64, 64, resize)

        assertEquals(2, regions.size)
        assertEquals(0.90f, regions[0].confidence, 1e-5f)
        assertEquals(0.80f, regions[1].confidence, 1e-5f)
        assertTrue(regions[0].quadrilateral.topLeft.x < 8.0f)
        assertTrue(regions[0].quadrilateral.topLeft.y < 8.0f)
        assertTrue(regions[0].quadrilateral.bottomRight.x > 27.0f)
        assertTrue(regions[0].quadrilateral.bottomRight.y > 15.0f)
        assertTrue(regions[0].quadrilateral.points.all { it.x in 0.0f..63.0f && it.y in 0.0f..63.0f })
    }

    @Test
    fun rejectsWeakTinyAndInvalidProbabilityMaps() {
        val weak = FloatArray(64 * 64)
        fill(weak, 10, 10, 30, 20, 0.40f)
        assertTrue(OcrDetectorPostprocessor().process(FloatBuffer.wrap(weak), 64, 64, resize).isEmpty())

        val tiny = FloatArray(64 * 64)
        fill(tiny, 10, 10, 12, 12, 0.90f)
        assertTrue(OcrDetectorPostprocessor().process(FloatBuffer.wrap(tiny), 64, 64, resize).isEmpty())

        weak[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            OcrDetectorPostprocessor().process(FloatBuffer.wrap(weak), 64, 64, resize)
        }
    }

    @Test
    fun mapsTensorGeometryBackToDecodedImage() {
        val scaledResize = DetectorResizePlan(32, 64, 64, 128)
        val map = FloatArray(64 * 128)
        fill(map, 20, 32, 44, 48, 0.90f, width = 64)

        val region = OcrDetectorPostprocessor().process(FloatBuffer.wrap(map), 64, 128, scaledResize).single()

        assertTrue(region.quadrilateral.topLeft.x < 10.0f)
        assertTrue(region.quadrilateral.topLeft.y < 16.0f)
        assertTrue(region.quadrilateral.bottomRight.x > 21.0f)
        assertTrue(region.quadrilateral.bottomRight.y > 23.0f)
    }

    private fun fill(
        map: FloatArray,
        left: Int,
        top: Int,
        rightExclusive: Int,
        bottomExclusive: Int,
        probability: Float,
        width: Int = 64,
    ) {
        for (y in top until bottomExclusive) {
            for (x in left until rightExclusive) map[y * width + x] = probability
        }
    }
}
