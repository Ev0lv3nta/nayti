package app.nayti.model.runtime.proof

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.ml.runtime.ocr.DetectedTextRegion
import app.nayti.ml.runtime.ocr.OcrDetectorPostprocessor
import app.nayti.ml.runtime.ocr.OcrDetectorPreprocessor
import app.nayti.ml.runtime.ocr.OcrOrtRuntime
import app.nayti.ml.runtime.ocr.OcrPoint
import app.nayti.ml.runtime.ocr.OcrQuadrilateral
import app.nayti.ml.runtime.ocr.OcrRecognizerImage
import app.nayti.ml.runtime.ocr.OcrRecognizerPreprocessor
import app.nayti.ml.runtime.ocr.OcrTensorBufferPool
import app.nayti.ml.runtime.pack.AlphaModelPackTrust
import app.nayti.ml.runtime.pack.AndroidModelPackPolicy
import app.nayti.ml.runtime.pack.AndroidModelPackStorageBudget
import app.nayti.ml.runtime.pack.FileModelPackSource
import app.nayti.ml.runtime.pack.ModelPackInstaller
import app.nayti.ml.runtime.pack.OrtKnownAnswerPayloadValidator
import app.nayti.ml.runtime.semantic.User2Contract
import app.nayti.ml.runtime.semantic.User2OrtRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignedModelPackInstrumentedTest {
    @Test
    fun importsSignedPackOnlyAfterRuntimeKnownAnswersPass() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val packPath = Paths.get(requireNotNull(arguments.getString("packPath")) {
            "instrumentation argument packPath is required"
        })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.toPath().resolve("model-pack-proof")
        deleteTree(root)

        val installer =
            ModelPackInstaller(
                root = root,
                trustedKeys = AlphaModelPackTrust.keys,
                policy = AndroidModelPackPolicy.current(appVersionCode = 1),
                storageBudget = AndroidModelPackStorageBudget(context),
                payloadValidator = OrtKnownAnswerPayloadValidator(),
            )

        try {
            val installed = installer.install(FileModelPackSource(packPath))

            assertEquals("nayti-offline-search", installed.packId)
            assertEquals("0.1.0-alpha.2", installed.packVersion)
            assertEquals(EXPECTED_MANIFEST_SHA256, installed.manifestSha256)
            assertEquals(EXPECTED_PAYLOAD_BYTES, installed.payloadBytes)
            assertTrue(Files.isRegularFile(installed.directory.resolve("payload/models/siglip2_image.ort")))
            validateProductionOcrPath(installed.directory.resolve("payload"))
            validateProductionUser2Path(installed.directory.resolve("payload"))
        } finally {
            deleteTree(root)
        }
    }

    private fun validateProductionUser2Path(payload: Path) {
        User2OrtRuntime.open(payload).use { runtime ->
            val contentTokens = runtime.contentTokenCount("договор аренды квартиры")
            assertTrue(contentTokens in 1..User2Contract.MaximumContentTokens)
            assertTrue(
                runtime.contentTokenCount((1..160).joinToString(" ") { index -> "слово$index" }) >
                    User2Contract.MaximumContentTokens,
            )

            val document = runtime.encodeDocument("Квартальный отчёт по продажам")
            val repeated = runtime.encodeDocument("Квартальный отчёт по продажам")
            val query = runtime.encodeQuery("рост продаж за квартал")
            assertEquals(User2Contract.EmbeddingDimension, document.normalized.size)
            assertEquals(User2Contract.EmbeddingDimension, document.quantized.size)
            assertTrue(document.quantized.contentEquals(repeated.quantized))
            assertFalse(document.quantized.contentEquals(query.quantized))
            val squaredNorm = document.normalized.sumOf { value -> value.toDouble() * value.toDouble() }
            assertTrue(kotlin.math.abs(squaredNorm - 1.0) < 1e-5)
        }
    }

    private fun validateProductionOcrPath(payload: Path) {
        val bitmap = Bitmap.createBitmap(736, 736, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffffffff.toInt())
        try {
            OcrOrtRuntime.open(payload).use { runtime ->
                OcrTensorBufferPool().use { pool ->
                    OcrDetectorPreprocessor(pool).prepare(bitmap).use { input ->
                        OcrDetectorPostprocessor().use { postprocessor ->
                            runtime.detect(input, postprocessor)
                        }
                    }
                    val manualRegion =
                        DetectedTextRegion(
                            OcrQuadrilateral(
                                OcrPoint(20.0f, 20.0f),
                                OcrPoint(700.0f, 20.0f),
                                OcrPoint(700.0f, 180.0f),
                                OcrPoint(20.0f, 180.0f),
                            ),
                            confidence = 1.0f,
                        )
                    OcrRecognizerImage.from(bitmap).use { image ->
                        OcrRecognizerPreprocessor(pool).prepare(image, listOf(manualRegion)).use { input ->
                            assertEquals(1, runtime.recognize(input).size)
                        }
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val EXPECTED_MANIFEST_SHA256 = "1f87cfe37659bee690441e464ae66415c1623e8ae751320a9483adc6aff79d83"
        const val EXPECTED_PAYLOAD_BYTES = 1_013_966_012L
    }
}
