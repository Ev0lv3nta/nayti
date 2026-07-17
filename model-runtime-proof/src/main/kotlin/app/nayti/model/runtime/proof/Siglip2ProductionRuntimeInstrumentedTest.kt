package app.nayti.model.runtime.proof

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.ml.runtime.visual.Siglip2ImageOrtRuntime
import app.nayti.ml.runtime.visual.Siglip2ImagePreprocessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Siglip2ProductionRuntimeInstrumentedTest {
    @Test
    fun androidPreprocessingAndProductionImageRuntimeMatchOfficialReference() {
        val arguments = InstrumentationRegistry.getArguments()
        val root = File(checkNotNull(arguments.getString("siglipKatRoot"))).canonicalFile
        val image = BitmapFactory.decodeFile(File(root, "siglip2-input.png").path)
        checkNotNull(image)
        val officialPixels = readFloats(File(root, "siglip2-pixel-values.raw"), 3 * 256 * 256)
        val expectedEmbedding = readFloats(File(root, "siglip2-image-embedding.raw"), 768)

        val actualPixels = Siglip2ImagePreprocessor.preprocess(image)
        val pixelMeanAbsoluteError =
            actualPixels.indices.sumOf { index ->
                kotlin.math.abs(actualPixels[index] - officialPixels[index]).toDouble()
            } / actualPixels.size
        val actualEmbedding =
            Siglip2ImageOrtRuntime.open(File(root, "payload").toPath()).use { runtime ->
                runtime.encode(image).normalized
            }
        image.recycle()
        val cosine = actualEmbedding.indices.sumOf { index ->
            actualEmbedding[index].toDouble() * expectedEmbedding[index]
        }
        Log.i(Tag, "pixelMae=$pixelMeanAbsoluteError embeddingCosine=$cosine")

        assertTrue("Android resize diverged from official processor: $pixelMeanAbsoluteError", pixelMeanAbsoluteError < 0.08)
        assertTrue("Production visual embedding cosine is too low: $cosine", cosine >= 0.995)
    }

    private fun readFloats(file: File, count: Int): FloatArray {
        check(file.isFile && file.length() == count * Float.SIZE_BYTES.toLong())
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(count).also(buffer::get)
    }

    private companion object {
        const val Tag = "NaytiSiglip2Proof"
    }
}
