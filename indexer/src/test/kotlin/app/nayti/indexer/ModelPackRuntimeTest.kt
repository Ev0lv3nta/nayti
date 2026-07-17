package app.nayti.indexer

import app.nayti.ml.runtime.pack.ModelPackSource
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPackRuntimeTest {
    @Test
    fun restoresNewestInstalledPackAndPublishesSuccessfulImport() = runTest {
        val registry = FakeRegistry(mutableListOf(pack("0.1.0-alpha.1", 1)))
        val installed = pack("0.1.0-alpha.2", 2)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtime =
            ModelPackRuntime(
                installer = RegisteredModelPackInstaller { installed.also(registry.values::add) },
                registry = registry,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )

        runtime.start()
        advanceUntilIdle()
        assertEquals("0.1.0-alpha.1", runtime.state.value.installed?.packVersion)

        runtime.install(ModelPackSource { ByteArrayInputStream(byteArrayOf()) })
        assertEquals(ModelPackRuntimeStatus.Installing, runtime.state.value.status)
        advanceUntilIdle()

        assertEquals(ModelPackRuntimeStatus.Ready, runtime.state.value.status)
        assertEquals("0.1.0-alpha.2", runtime.state.value.installed?.packVersion)
    }

    @Test
    fun failedImportPreservesPreviousInstalledPack() = runTest {
        val previous = pack("0.1.0-alpha.1", 1)
        val registry = FakeRegistry(mutableListOf(previous))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtime =
            ModelPackRuntime(
                installer = RegisteredModelPackInstaller { throw IllegalStateException("invalid") },
                registry = registry,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
        runtime.start()
        advanceUntilIdle()

        runtime.install(ModelPackSource { ByteArrayInputStream(byteArrayOf()) })
        advanceUntilIdle()

        assertEquals(ModelPackRuntimeStatus.Failed, runtime.state.value.status)
        assertEquals(previous, runtime.state.value.installed)
        assertEquals("ILLEGALSTATEEXCEPTION", runtime.state.value.errorCode)
    }

    @Test
    fun missingNativeRuntimeIsReportedWithoutLosingPreviousPack() = runTest {
        val previous = pack("0.1.0-alpha.1", 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtime =
            ModelPackRuntime(
                installer = RegisteredModelPackInstaller { throw NoClassDefFoundError("ORT") },
                registry = FakeRegistry(mutableListOf(previous)),
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
        runtime.start()
        advanceUntilIdle()

        runtime.install(ModelPackSource { ByteArrayInputStream(byteArrayOf()) })
        advanceUntilIdle()

        assertEquals(ModelPackRuntimeStatus.Failed, runtime.state.value.status)
        assertEquals(previous, runtime.state.value.installed)
        assertEquals("RUNTIME_UNAVAILABLE", runtime.state.value.errorCode)
    }

    private fun pack(version: String, installedAt: Long) =
        ModelPackEntity(
            packId = "nayti-offline-search",
            packVersion = version,
            keyId = "1".repeat(32),
            manifestSha256 = if (installedAt == 1L) "a".repeat(64) else "b".repeat(64),
            relativeDirectory = "nayti-offline-search/$version-aaaaaaaaaaaaaaaa",
            payloadBytes = 100,
            installedAtMillis = installedAt,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private class FakeRegistry(val values: MutableList<ModelPackEntity>) : ModelPackDao {
        override suspend fun pack(packId: String, packVersion: String): ModelPackEntity? =
            values.firstOrNull { it.packId == packId && it.packVersion == packVersion }

        override suspend fun packs(): List<ModelPackEntity> = values.toList()

        override suspend fun insertIfAbsent(pack: ModelPackEntity): Long = error("not used")
    }
}
