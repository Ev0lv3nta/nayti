package app.nayti.model.runtime.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.ml.runtime.pack.AlphaModelPackTrust
import app.nayti.ml.runtime.pack.AndroidModelPackPolicy
import app.nayti.ml.runtime.pack.AndroidModelPackStorageBudget
import app.nayti.ml.runtime.pack.FileModelPackSource
import app.nayti.ml.runtime.pack.ModelPackInstaller
import app.nayti.ml.runtime.pack.ModelPackPayloadValidator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
                payloadValidator = ModelPackPayloadValidator { candidate ->
                    OrtKnownAnswerGate().validatePackPayload(candidate.payloadDirectory.toFile())
                },
            )

        try {
            val installed = installer.install(FileModelPackSource(packPath))

            assertEquals("nayti-offline-search", installed.packId)
            assertEquals("0.1.0-alpha.1", installed.packVersion)
            assertEquals(EXPECTED_MANIFEST_SHA256, installed.manifestSha256)
            assertEquals(EXPECTED_PAYLOAD_BYTES, installed.payloadBytes)
            assertTrue(Files.isRegularFile(installed.directory.resolve("payload/models/siglip2_image.ort")))
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val EXPECTED_MANIFEST_SHA256 = "63006241caadcce395dce51bb8df4857805c998353b95941830b71d6c82c5b86"
        const val EXPECTED_PAYLOAD_BYTES = 1_013_962_735L
    }
}
