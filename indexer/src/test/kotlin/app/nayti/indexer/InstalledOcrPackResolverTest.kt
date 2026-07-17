package app.nayti.indexer

import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledOcrPackResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun resolvesRegisteredImmutablePayload() = runTest {
        val fixture = fixture()

        val resolved = InstalledOcrPackResolver(FakeRegistry(fixture.entry), fixture.root)
            .resolve(PackId, PackVersion)

        assertEquals(fixture.payload, resolved.payloadDirectory)
        assertEquals(fixture.entry.manifestSha256, resolved.componentHash)
    }

    @Test
    fun rejectsManifestChangedAfterRegistration() = runTest {
        val fixture = fixture()
        Files.writeString(fixture.manifest, "changed")
        val resolver = InstalledOcrPackResolver(FakeRegistry(fixture.entry), fixture.root)

        val failure = runCatching { resolver.resolve(PackId, PackVersion) }.exceptionOrNull()

        assertTrue(failure is ModelPackUnavailableException)
    }

    @Test
    fun rejectsRegistryPathOutsideCanonicalIdentity() = runTest {
        val fixture = fixture()
        val escaped = fixture.entry.copy(relativeDirectory = "../elsewhere")
        val resolver = InstalledOcrPackResolver(FakeRegistry(escaped), fixture.root)

        val failure = runCatching { resolver.resolve(PackId, PackVersion) }.exceptionOrNull()

        assertTrue(failure is ModelPackUnavailableException)
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder("model-packs").toPath().toAbsolutePath()
        val bytes = "signed canonical manifest".encodeToByteArray()
        val hash = sha256(bytes)
        val directory = root.resolve("$PackId/$PackVersion-${hash.take(16)}")
        val payload = directory.resolve("payload")
        Files.createDirectories(payload)
        val manifest = directory.resolve("manifest.json")
        Files.write(manifest, bytes)
        val entry =
            ModelPackEntity(
                packId = PackId,
                packVersion = PackVersion,
                keyId = "1".repeat(32),
                manifestSha256 = hash,
                relativeDirectory = root.relativize(directory).joinToString("/"),
                payloadBytes = 1_000,
                installedAtMillis = 1,
                status = ModelPackStatus.INSTALLED_CANDIDATE,
            )
        return Fixture(root, payload, manifest, entry)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val root: Path,
        val payload: Path,
        val manifest: Path,
        val entry: ModelPackEntity,
    )

    private class FakeRegistry(private val entry: ModelPackEntity?) : ModelPackDao {
        override suspend fun pack(packId: String, packVersion: String): ModelPackEntity? =
            entry?.takeIf { it.packId == packId && it.packVersion == packVersion }

        override suspend fun packs(): List<ModelPackEntity> = listOfNotNull(entry)

        override suspend fun insertIfAbsent(pack: ModelPackEntity): Long = error("not used")
    }

    private companion object {
        const val PackId = "nayti-offline-search"
        const val PackVersion = "0.1.0-alpha.2"
    }
}
