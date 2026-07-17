package app.nayti.storage

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerceptualHashPublicationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase
    private lateinit var catalog: CatalogDao
    private lateinit var index: IndexStateDao
    private lateinit var hashes: PerceptualHashDao

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun publicationIsAtomicDurableEpochBoundAndImmediatelyAccessSafe() = runBlocking {
        val firstId = insertAsset(7, SourceA)
        val secondId = insertAsset(8, SourceB)
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startWindow()
        val firstClaim = claim(firstId, "first")
        val first = draft(firstId, SourceA, 0x1234)

        val firstPublication =
            hashes.commit(
                leaseToken = checkNotNull(firstClaim.leaseToken),
                publicationToken = "phash-first",
                draft = first,
                expectedIdentity = PerceptualHashCodec.identity(first),
                nowMillis = 20,
            )

        assertEquals(1L, firstPublication?.publicationEpoch)
        assertEquals(IndexWorkState.DONE, index.work(firstId, IndexChannel.PHASH)?.state)
        assertNull(
            hashes.commit(
                checkNotNull(firstClaim.leaseToken),
                "phash-duplicate-lease",
                first,
                PerceptualHashCodec.identity(first),
                21,
            ),
        )

        val secondClaim = claim(secondId, "second")
        val second = draft(secondId, SourceB, 0x1235)
        assertEquals(
            2L,
            hashes.commit(
                checkNotNull(secondClaim.leaseToken),
                "phash-second",
                second,
                PerceptualHashCodec.identity(second),
                22,
            )?.publicationEpoch,
        )
        assertEquals(listOf(firstId), current(1).map(CurrentPerceptualHash::assetId))
        assertEquals(listOf(firstId, secondId), current(2).map(CurrentPerceptualHash::assetId))

        reopenDatabase()
        assertEquals(2L, hashes.maximumPublicationEpoch(PipelineVersion, ComponentHash))
        assertEquals(listOf(firstId, secondId), current(2).map(CurrentPerceptualHash::assetId))

        val changed = checkNotNull(catalog.asset(firstId)).copy(sourceFingerprint = ChangedSource)
        assertEquals(1, catalog.updateAsset(changed))
        assertEquals(listOf(secondId), current(2).map(CurrentPerceptualHash::assetId))

        catalog.recordAccessObservation("None", AccessRevision + 1, 30)
        assertTrue(current(2).isEmpty())
    }

    private suspend fun current(epoch: Long): List<CurrentPerceptualHash> =
        hashes.current(PipelineVersion, ComponentHash, epoch)

    private suspend fun claim(assetId: Long, nonce: String): IndexChannelWorkEntity {
        index.ensureWork(assetId, IndexChannel.PHASH, AccessRevision, PipelineVersion, ComponentHash, 2)
        return index.claimBatch(WindowId, IndexChannel.PHASH, nonce, 10, 100, 1).single()
    }

    private suspend fun startWindow() {
        index.insertOperation(
            IndexOperationEntity(
                operationId = OperationId,
                profileId = "balanced-v1",
                targetPackId = "nayti-offline-search",
                targetPackVersion = "0.1.0-alpha.1",
                denominatorCatalogRevision = 1,
                denominatorAssetCount = 2,
                state = IndexOperationState.PLANNED,
                autoResume = true,
                createdAtMillis = 0,
                updatedAtMillis = 0,
                completedAtMillis = null,
            ),
        )
        index.startExecutionWindow(
            IndexExecutionWindowEntity(
                windowId = WindowId,
                operationId = OperationId,
                hostType = "TEST",
                leaseToken = "window-lease",
                state = IndexExecutionWindowState.RUNNING,
                startedAtMillis = 0,
                expiresAtMillis = 1_000,
                finishedAtMillis = null,
            ),
            0,
        )
    }

    private fun draft(assetId: Long, sourceFingerprint: String, hashBits: Long) =
        PerceptualHashDraft(
            assetId = assetId,
            sourceFingerprint = sourceFingerprint,
            accessRevision = AccessRevision,
            pipelineVersion = PipelineVersion,
            componentHash = ComponentHash,
            hashBits = hashBits,
        )

    private suspend fun insertAsset(mediaStoreId: Long, sourceFingerprint: String): Long =
        catalog.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = mediaStoreId,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 1_440,
                height = 1_080,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = 1,
                dateTakenMillis = null,
                dateModifiedSeconds = 1,
                displayName = "image-$mediaStoreId.jpg",
                bucketId = null,
                bucketDisplayName = null,
                relativePath = null,
                sourceFingerprint = sourceFingerprint,
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = 1,
            ),
        )

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        catalog = database.catalogDao()
        index = database.indexStateDao()
        hashes = database.perceptualHashDao()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private companion object {
        const val DatabaseName = "phash-publication-instrumented.db"
        const val OperationId = "operation-phash"
        const val WindowId = "window-phash"
        const val AccessRevision = 7L
        const val PipelineVersion = "phash-v1"
        const val ComponentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SourceA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SourceB = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val ChangedSource = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
