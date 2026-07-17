package app.nayti.ml.runtime.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRecognizerContractTest {
    @Test
    fun projectiveTransformMapsAllUnitSquareCorners() {
        val quadrilateral =
            OcrQuadrilateral(
                OcrPoint(10.0f, 20.0f),
                OcrPoint(110.0f, 10.0f),
                OcrPoint(100.0f, 70.0f),
                OcrPoint(20.0f, 80.0f),
            )
        val transform = PerspectiveTransform.fromUnitSquare(quadrilateral)

        assertPoint(10.0, 20.0, transform.map(0.0, 0.0))
        assertPoint(110.0, 10.0, transform.map(1.0, 0.0))
        assertPoint(100.0, 70.0, transform.map(1.0, 1.0))
        assertPoint(20.0, 80.0, transform.map(0.0, 1.0))
    }

    private fun assertPoint(x: Double, y: Double, actual: DoublePointForTransform) {
        assertEquals(x, actual.x, 1e-8)
        assertEquals(y, actual.y, 1e-8)
    }
}
