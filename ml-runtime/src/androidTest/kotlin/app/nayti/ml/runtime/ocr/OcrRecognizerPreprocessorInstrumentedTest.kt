package app.nayti.ml.runtime.ocr

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRecognizerPreprocessorInstrumentedTest {
    @Test
    fun perspectiveCropNormalizesBgrAndZeroPadsRightSide() {
        val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff3366cc.toInt())
        val region =
            DetectedTextRegion(
                OcrQuadrilateral(
                    OcrPoint(10.0f, 10.0f),
                    OcrPoint(70.0f, 10.0f),
                    OcrPoint(70.0f, 30.0f),
                    OcrPoint(10.0f, 30.0f),
                ),
                confidence = 0.9f,
            )
        try {
            OcrRecognizerImage.from(bitmap).use { image ->
                OcrTensorBufferPool().use { pool ->
                    OcrRecognizerPreprocessor(pool).prepare(image, listOf(region)).use { input ->
                        assertEquals(144, input.validWidths.single())
                        val values = input.tensor.readableBuffer()
                        val plane = OcrRecognizerContract.InputHeight * OcrRecognizerContract.InputWidth
                        assertEquals(0xcc / 127.5f - 1.0f, values[0], 1e-5f)
                        assertEquals(0x66 / 127.5f - 1.0f, values[plane], 1e-5f)
                        assertEquals(0x33 / 127.5f - 1.0f, values[2 * plane], 1e-5f)
                        assertEquals(0.0f, values[200], 0.0f)
                        assertEquals(0.0f, values[plane + 200], 0.0f)
                        assertEquals(0.0f, values[2 * plane + 200], 0.0f)
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
