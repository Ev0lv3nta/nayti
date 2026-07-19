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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase
    private lateinit var dao: CatalogDao
    private var nowMillis = 10_000L

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
    fun mediaIdsAreUniquePerVolumeAndAssetIdsNeverChange() = runBlocking {
        completeRun("external_primary", 1, listOf(draft("external_primary", 7)))
        completeRun("0123-4567", 1, listOf(draft("0123-4567", 7)))

        val primary = checkNotNull(dao.asset("external_primary", 7))
        val removable = checkNotNull(dao.asset("0123-4567", 7))
        assertNotEquals(primary.assetId, removable.assetId)

        completeRun(
            "external_primary",
            2,
            listOf(draft("external_primary", 7).copy(displayName = "renamed.jpg")),
        )
        assertEquals(primary.assetId, dao.asset("external_primary", 7)?.assetId)
        assertEquals(2L, dao.counts().available)
    }

    @Test
    fun fullScopeNeedsTwoStableMissingObservationsBeforeDelete() = runBlocking {
        completeRun("external_primary", 1, listOf(draft("external_primary", 1)))

        completeRun("external_primary", 2, emptyList())
        val firstMissing = checkNotNull(dao.asset("external_primary", 1))
        assertEquals(CatalogAvailability.MISSING_UNCONFIRMED, firstMissing.availability)
        assertEquals(1, firstMissing.missingFullObservationCount)
        assertTrue(dao.availableAssets().isEmpty())

        completeRun("external_primary", 3, emptyList())
        val deleted = checkNotNull(dao.asset("external_primary", 1))
        assertEquals(CatalogAvailability.DELETED, deleted.availability)
        assertEquals(2, deleted.missingFullObservationCount)
    }

    @Test
    fun selectedScopeQuarantinesAndReactivatesSameIdentity() = runBlocking {
        completeRun("external_primary", 1, listOf(draft("external_primary", 2)))
        val assetId = checkNotNull(dao.asset("external_primary", 2)).assetId

        completeRun(
            volumeName = "external_primary",
            generation = 2,
            observations = emptyList(),
            accessScope = "Selected",
        )
        val quarantined = checkNotNull(dao.asset("external_primary", 2))
        assertEquals(CatalogAvailability.OUT_OF_SCOPE, quarantined.availability)
        assertNotNull(quarantined.quarantineStartedAtMillis)
        assertEquals(1, dao.updateAsset(quarantined.copy(derivedDataPurgedAtMillis = tick())))

        completeRun(
            volumeName = "external_primary",
            generation = 3,
            observations = listOf(draft("external_primary", 2)),
            accessScope = "Selected",
        )
        val returned = checkNotNull(dao.asset("external_primary", 2))
        assertEquals(assetId, returned.assetId)
        assertEquals(CatalogAvailability.AVAILABLE, returned.availability)
        assertEquals(null, returned.quarantineStartedAtMillis)
        assertEquals(null, returned.derivedDataPurgedAtMillis)
    }

    @Test
    fun interruptedRunCannotApplyNegativeDiffOrDuplicateAsset() = runBlocking {
        completeRun("external_primary", 1, listOf(draft("external_primary", 3)))
        val assetId = checkNotNull(dao.asset("external_primary", 3)).assetId
        val interruptedRun = beginRun("external_primary-interrupted", "Full", CatalogInventoryMode.FULL)
        dao.applyObservations(
            interruptedRun,
            listOf(draft("external_primary", 3).copy(displayName = "during-crash.jpg")),
            tick(),
        )

        reopenDatabase()
        assertEquals(1, dao.abandonRunningInventoryRuns(tick()))
        assertEquals(
            CatalogInventoryStatus.ABANDONED,
            dao.inventoryRun(interruptedRun)?.status,
        )
        completeRun("external_primary", 2, listOf(draft("external_primary", 3)))
        assertEquals(assetId, dao.asset("external_primary", 3)?.assetId)
        assertEquals(1L, dao.counts().total)
    }

    @Test
    fun offlineVolumeAndNoAccessImmediatelyRemoveEligibility() = runBlocking {
        completeRun("external_primary", 1, listOf(draft("external_primary", 4)))
        dao.setVolumeOffline("external_primary", tick())
        assertEquals(CatalogAvailability.VOLUME_OFFLINE, dao.asset("external_primary", 4)?.availability)

        completeRun("external_primary", 2, listOf(draft("external_primary", 4)))
        dao.applyNoAccess(tick())
        assertEquals(CatalogAvailability.OUT_OF_SCOPE, dao.asset("external_primary", 4)?.availability)
        assertTrue(dao.availableAssets().isEmpty())
    }

    @Test
    fun indexingScopePersistsExactCutoffAndLeavesCatalogIntact() = runBlocking {
        completeRun(
            "external_primary",
            1,
            listOf(
                draft("external_primary", 1).copy(dateTakenMillis = 1_000),
                draft("external_primary", 2).copy(dateTakenMillis = 5_000),
                draft("external_primary", 3).copy(dateTakenMillis = null, dateModifiedSeconds = null),
            ),
        )
        val initial = dao.indexingScopeSummary()
        assertEquals(IndexingScopeMode.ALL, initial.mode)
        assertEquals(3L, initial.eligibleAssets)

        val changed = dao.updateIndexingScope(IndexingScopeMode.SINCE_DATE, 3_000, tick())

        assertEquals(2L, changed.revision)
        assertEquals(listOf(2L), dao.indexableAssets().map { it.mediaStoreId })
        val scoped = dao.indexingScopeSummary()
        assertEquals(3L, scoped.totalAvailable)
        assertEquals(1L, scoped.eligibleAssets)
        assertEquals(1L, scoped.unknownDateAssets)
        assertEquals(3L, dao.counts().available)

        reopenDatabase()
        assertEquals(3_000L, dao.currentIndexingScope().takenFromMillis)
        assertEquals(listOf(2L), dao.indexableAssets().map { it.mediaStoreId })

        val expanded = dao.updateIndexingScope(IndexingScopeMode.ALL, null, tick())
        assertEquals(3L, expanded.revision)
        assertEquals(3, dao.indexableAssets().size)
    }

    private suspend fun completeRun(
        volumeName: String,
        generation: Long,
        observations: List<CatalogAssetDraft>,
        accessScope: String = "Full",
    ) {
        val runId = beginRun("$volumeName-$generation-${tick()}", accessScope, CatalogInventoryMode.FULL)
        dao.applyObservations(runId, observations, tick())
        dao.completeVolumeInventory(
            runId = runId,
            volumeName = volumeName,
            mediaStoreVersion = "v1",
            generation = generation,
            accessScope = accessScope,
            isFullInventory = true,
            observedAssetCount = observations.size,
            nowMillis = tick(),
        )
    }

    private suspend fun beginRun(token: String, scope: String, mode: String): Long =
        dao.insertInventoryRun(
            CatalogInventoryRunEntity(
                token = token,
                accessScope = scope,
                accessRevision = 1,
                mode = mode,
                startedAtMillis = tick(),
                finishedAtMillis = null,
                status = CatalogInventoryStatus.RUNNING,
                observedAssetCount = 0,
            ),
        )

    private fun draft(volumeName: String, mediaStoreId: Long) =
        CatalogAssetDraft(
            volumeName = volumeName,
            mediaStoreId = mediaStoreId,
            mimeType = "image/jpeg",
            sizeBytes = 1_024,
            width = 100,
            height = 200,
            orientationDegrees = 0,
            generationAdded = 1,
            generationModified = 1,
            dateTakenMillis = 1_000,
            dateModifiedSeconds = 2,
            displayName = "$mediaStoreId.jpg",
            bucketId = 10,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            sourceFingerprint = "fingerprint-$volumeName-$mediaStoreId",
            isPending = false,
            isTrashed = false,
        )

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setMultipleConnectionPool(maxNumOfReaders = 2, maxNumOfWriters = 1)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        dao = database.catalogDao()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun tick(): Long = ++nowMillis

    private companion object {
        const val DatabaseName = "catalog-instrumented.db"
    }
}
