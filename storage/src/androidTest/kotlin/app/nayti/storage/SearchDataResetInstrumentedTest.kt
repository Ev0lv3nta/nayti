package app.nayti.storage

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchDataResetInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseName)
        database =
            Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun resetRemovesDerivedStateButPreservesCatalogAndModelPack() = runBlocking {
        val assetId = database.catalogDao().insertAsset(asset())
        val pack = modelPack()
        database.modelPackDao().registerInstalledCandidate(pack)
        database.indexStateDao().replaceWork(
            IndexChannelWorkEntity(
                assetId = assetId,
                channel = IndexChannel.OCR,
                state = IndexWorkState.PENDING,
                sourceFingerprint = "source-a",
                accessRevision = 1,
                pipelineVersion = "ocr-v1",
                componentHash = ComponentHash,
                attempt = 0,
                leaseToken = null,
                leaseExpiresAtMillis = null,
                executionWindowId = null,
                publicationToken = null,
                stagedArtifactPath = null,
                stagedArtifactLength = null,
                stagedArtifactSha256 = null,
                nextEligibleAtMillis = null,
                errorCode = null,
                updatedAtMillis = 1,
            ),
        )
        database.vectorIndexDao().replaceActivePointer(
            ActiveSnapshotPointerEntity(snapshotId = "snapshot-a"),
        )

        assertEquals(1L, database.searchDataResetDao().channelWorkCount())
        assertNotNull(database.vectorIndexDao().activePointer())
        database.searchDataResetDao().reset()

        assertEquals(0L, database.searchDataResetDao().channelWorkCount())
        assertNull(database.vectorIndexDao().activePointer())
        assertEquals(assetId, database.catalogDao().asset(assetId)?.assetId)
        assertEquals(pack, database.modelPackDao().pack(pack.packId, pack.packVersion))
    }

    private fun asset() =
        CatalogAssetEntity(
            volumeName = "external_primary",
            mediaStoreId = 7,
            mimeType = "image/jpeg",
            sizeBytes = 100,
            width = 10,
            height = 10,
            orientationDegrees = 0,
            generationAdded = 1,
            generationModified = 1,
            dateTakenMillis = null,
            dateModifiedSeconds = 1,
            displayName = "photo.jpg",
            bucketId = null,
            bucketDisplayName = null,
            relativePath = "DCIM/Camera/",
            sourceFingerprint = "source-a",
            availability = CatalogAvailability.AVAILABLE,
            lastSeenInventoryRunId = 1,
            missingFullObservationCount = 0,
            quarantineStartedAtMillis = null,
            sourceObservedAtMillis = 1,
        )

    private fun modelPack() =
        ModelPackEntity(
            packId = "nayti-offline-search",
            packVersion = "test",
            keyId = "test-key",
            manifestSha256 = ComponentHash,
            relativeDirectory = "test-pack",
            payloadBytes = 1_024,
            installedAtMillis = 1,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private companion object {
        const val DatabaseName = "search-data-reset.db"
        const val ComponentHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
