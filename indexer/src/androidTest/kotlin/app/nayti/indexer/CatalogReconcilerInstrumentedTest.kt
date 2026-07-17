package app.nayti.indexer

import android.content.Context
import android.net.Uri
import app.nayti.platform.media.AccessRevisionGate
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaInventory
import app.nayti.platform.media.MediaKey
import app.nayti.platform.media.MediaObservation
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.platform.media.MediaStoreGateway
import app.nayti.platform.media.MediaVolumeSnapshot
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.StorageContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogReconcilerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var storage: CatalogStorage
    private lateinit var gateway: FakeMediaStoreGateway
    private var permission = permission(MediaAccessScope.Full)
    private lateinit var accessGate: AccessRevisionGate
    private var nowMillis = 100_000L
    private var token = 0

    @Before
    fun setUp() {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        storage = CatalogStorage.open(context)
        gateway = FakeMediaStoreGateway()
        accessGate = AccessRevisionGate(permission) { permission }
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(StorageContract.DatabaseFileName)
    }

    @Test
    fun selectedScopeHidesUnseenAssetWithoutDeletingIt() = runBlocking {
        gateway.observations = listOf(observation(1), observation(2))
        reconciler().reconcile(forceFull = true)
        val secondAssetId = checkNotNull(storage.catalogDao.asset("external_primary", 2)).assetId

        permission = permission(MediaAccessScope.Selected)
        gateway.volume = gateway.volume.copy(generation = 2)
        gateway.observations = listOf(observation(1).copy(generationModified = 2))
        val result = reconciler().reconcile(forceFull = true)

        assertEquals(1L, result.counts.available)
        assertEquals(1L, result.counts.outOfScope)
        val hidden = checkNotNull(storage.catalogDao.asset("external_primary", 2))
        assertEquals(secondAssetId, hidden.assetId)
        assertEquals(CatalogAvailability.OUT_OF_SCOPE, hidden.availability)
        assertEquals(0L, result.counts.deleted)
    }

    @Test
    fun revokeDuringInventoryInvalidatesPinBeforeNegativeDiff() = runBlocking {
        gateway.observations = listOf(observation(1), observation(2))
        val reconciler = reconciler()
        reconciler.reconcile(forceFull = true)
        val queryPin = accessGate.pin()

        gateway.volume = gateway.volume.copy(generation = 2)
        gateway.observations = emptyList()
        gateway.afterInventory = { permission = permission(MediaAccessScope.None) }
        expectEvidenceChange { reconciler.reconcile(forceFull = true) }

        assertFalse(accessGate.isCurrent(queryPin))
        assertEquals(MediaAccessScope.None, accessGate.pin().permission.scope)
        assertEquals(
            listOf(CatalogAvailability.AVAILABLE, CatalogAvailability.AVAILABLE),
            storage.catalogDao.allAssets().map { it.availability },
        )
    }

    @Test
    fun generationChangeDuringInventoryCannotConfirmMissingAsset() = runBlocking {
        gateway.observations = listOf(observation(1))
        val reconciler = reconciler()
        reconciler.reconcile(forceFull = true)

        gateway.volume = gateway.volume.copy(generation = 2)
        gateway.observations = emptyList()
        gateway.afterInventory = {
            gateway.volume = gateway.volume.copy(generation = 3)
        }
        expectEvidenceChange { reconciler.reconcile(forceFull = true) }

        assertEquals(
            CatalogAvailability.AVAILABLE,
            storage.catalogDao.asset("external_primary", 1)?.availability,
        )
        assertTrue(storage.catalogDao.availableAssets().isNotEmpty())
    }

    private fun reconciler() =
        CatalogReconciler(
            accessGate = accessGate,
            mediaStore = gateway,
            catalogDao = storage.catalogDao,
            clock = CatalogClock { ++nowMillis },
            tokenFactory = InventoryTokenFactory { "run-${++token}" },
        )

    private suspend fun expectEvidenceChange(block: suspend () -> Unit) {
        try {
            block()
            error("Expected InventoryEvidenceChangedException")
        } catch (_: InventoryEvidenceChangedException) {
            Unit
        }
    }

    private fun observation(id: Long) =
        MediaObservation(
            key = MediaKey("external_primary", id),
            mimeType = "image/jpeg",
            sizeBytes = 1_024,
            width = 100,
            height = 200,
            orientationDegrees = 0,
            generationAdded = 1,
            generationModified = gateway.volume.generation,
            dateTakenMillis = 1_000,
            dateModifiedSeconds = 2,
            displayName = "$id.jpg",
            bucketId = 10,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            isPending = false,
            isTrashed = false,
        )

    private fun permission(scope: MediaAccessScope) =
        MediaPermissionSnapshot(
            scope = scope,
            readImagesGranted = scope == MediaAccessScope.Full,
            selectedImagesGranted = scope == MediaAccessScope.Selected,
        )

    private class FakeMediaStoreGateway : MediaStoreGateway {
        var volume = MediaVolumeSnapshot("external_primary", "v1", 1)
        var observations: List<MediaObservation> = emptyList()
        var afterInventory: (() -> Unit)? = null

        override fun mountedVolumes(): List<MediaVolumeSnapshot> = listOf(volume)

        override fun inventory(
            volume: MediaVolumeSnapshot,
            modifiedAfterGeneration: Long?,
            cancellationSignal: android.os.CancellationSignal?,
        ): MediaInventory {
            val result =
                MediaInventory(
                    volume = volume,
                    isFullInventory = modifiedAfterGeneration == null,
                    observations =
                        observations.filter { observation ->
                            modifiedAfterGeneration == null ||
                                observation.generationModified > modifiedAfterGeneration
                        },
                )
            afterInventory?.also { afterInventory = null }?.invoke()
            return result
        }

        override fun contentUri(key: MediaKey): Uri = error("Not used by reconciliation tests")
    }
}
