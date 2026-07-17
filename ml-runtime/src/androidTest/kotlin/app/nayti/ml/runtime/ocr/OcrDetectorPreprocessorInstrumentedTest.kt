package app.nayti.ml.runtime.ocr

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrDetectorPreprocessorInstrumentedTest {
    @Test
    fun solidBitmapProducesBoundedBgrPlanarTensorWithoutTakingOwnership() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff3366cc.toInt())
        try {
            OcrTensorBufferPool().use { pool ->
                OcrDetectorPreprocessor(pool).prepare(bitmap).use { input ->
                    assertEquals(736, input.resize.tensorWidth)
                    assertEquals(1_472, input.resize.tensorHeight)
                    val values = input.tensor.readableBuffer()
                    val plane = input.resize.tensorWidth * input.resize.tensorHeight
                    assertEquals((0xcc / 255.0f - 0.485f) / 0.229f, values[0], 1e-6f)
                    assertEquals((0x66 / 255.0f - 0.456f) / 0.224f, values[plane], 1e-6f)
                    assertEquals((0x33 / 255.0f - 0.406f) / 0.225f, values[2 * plane], 1e-6f)
                }
            }
            assertFalse(bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }
}
