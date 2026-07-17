package app.nayti.indexer

import app.nayti.ml.runtime.pack.ModelPackCandidateInstaller
import app.nayti.ml.runtime.pack.ModelPackSource
import app.nayti.ml.runtime.pack.VerifiedModelPack
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPackInstallCoordinatorTest {
    @Test
    fun verifiedDirectoryIsRegisteredAsCandidateWithoutActivation() = runTest {
        val root = Path.of("/private/model-packs")
        val verified =
            VerifiedModelPack(
                packId = "nayti-offline-search",
                packVersion = "0.1.0-alpha.1",
                keyId = "1".repeat(32),
                manifestSha256 = "a".repeat(64),
                payloadBytes = 123,
                directory = root.resolve("nayti-offline-search/0.1.0-alpha.1-aaaaaaaaaaaaaaaa"),
            )
        val registry = FakeRegistry()
        val coordinator =
            ModelPackInstallCoordinator(
                installer = ModelPackCandidateInstaller { verified },
                registry = registry,
                modelPackRoot = root,
                nowMillis = { 42 },
            )

        val result = coordinator.install(ModelPackSource { ByteArrayInputStream(byteArrayOf()) })

        assertEquals("nayti-offline-search/0.1.0-alpha.1-aaaaaaaaaaaaaaaa", result.relativeDirectory)
        assertEquals(42, result.installedAtMillis)
        assertEquals(result, registry.value)
    }

    private class FakeRegistry : ModelPackDao {
        var value: ModelPackEntity? = null

        override suspend fun pack(packId: String, packVersion: String): ModelPackEntity? = value

        override suspend fun packs(): List<ModelPackEntity> = listOfNotNull(value)

        override suspend fun insertIfAbsent(pack: ModelPackEntity): Long {
            if (value == null) value = pack
            return if (value == pack) 1 else -1
        }

        override suspend fun registerInstalledCandidate(candidate: ModelPackEntity): ModelPackEntity {
            value = candidate
            return candidate
        }
    }
}
