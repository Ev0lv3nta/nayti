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
class IndexStateInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase
    private lateinit var catalog: CatalogDao
    private lateinit var index: IndexStateDao

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
    fun sqlPublicationIsAtomicDurableAndRejectsDuplicateLeaseCommit() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 2)

        val claim = index.claimBatch("window-a", IndexChannel.OCR, "claim-a", 10, 100, 8).single()
        val publication =
            index.commitSqlPublication(
                leaseToken = checkNotNull(claim.leaseToken),
                publicationToken = "publication-a",
                resultSha256 = ResultHash,
                resultLength = 42,
                nowMillis = 20,
            )

        assertEquals(1L, publication?.publicationEpoch)
        assertEquals(IndexWorkState.DONE, index.work(assetId, IndexChannel.OCR)?.state)
        assertNull(index.commitSqlPublication(checkNotNull(claim.leaseToken), "duplicate", ResultHash, 42, 21))
        reopenDatabase()

        assertEquals(publication, index.publication(assetId, IndexChannel.OCR))
        assertEquals(IndexWorkState.DONE, index.work(assetId, IndexChannel.OCR)?.state)
        assertEquals(1L, index.publicationClock()?.lastEpoch)
    }

    @Test
    fun expiredLeaseReturnsToQueueAndLateCommitCannotPublish() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 100)
        index.ensureWork(assetId, IndexChannel.PHASH, AccessRevision, "phash-v1", ComponentHash, 2)
        val stale = index.claimBatch("window-a", IndexChannel.PHASH, "claim-a", 10, 90, 1).single()

        assertEquals(1 to 1, index.recoverExpiredExecution(100))
        assertNull(index.commitSqlPublication(checkNotNull(stale.leaseToken), "late", ResultHash, 8, 101))
        assertEquals(IndexWorkState.PENDING, index.work(assetId, IndexChannel.PHASH)?.state)

        index.startExecutionWindow(window("window-b", 101, 500), 101)
        val fresh = index.claimBatch("window-b", IndexChannel.PHASH, "claim-b", 102, 100, 1).single()
        assertTrue(
            index.commitSqlPublication(checkNotNull(fresh.leaseToken), "publication-b", ResultHash, 8, 110) != null,
        )
    }

    @Test
    fun changedSourceOrAccessInvalidatesEvidenceBeforeCommit() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 2)
        val oldSource = index.claimBatch("window-a", IndexChannel.OCR, "claim-a", 10, 100, 1).single()

        val asset = checkNotNull(catalog.asset(assetId))
        assertEquals(1, catalog.updateAsset(asset.copy(sourceFingerprint = "source-b")))
        assertNull(index.commitSqlPublication(checkNotNull(oldSource.leaseToken), "stale-source", ResultHash, 1, 20))

        val refreshed = index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 21)
        assertEquals("source-b", refreshed.sourceFingerprint)
        val oldAccess = index.claimBatch("window-a", IndexChannel.OCR, "claim-b", 22, 100, 1).single()
        catalog.recordAccessObservation("Full", AccessRevision + 1, 23)
        assertNull(index.commitSqlPublication(checkNotNull(oldAccess.leaseToken), "stale-access", ResultHash, 1, 24))

        val current = index.ensureWork(assetId, IndexChannel.OCR, AccessRevision + 1, "ocr-v1", ComponentHash, 25)
        assertEquals(IndexWorkState.PENDING, current.state)
        assertEquals(AccessRevision + 1, current.accessRevision)
    }

    @Test
    fun retryBudgetBecomesVisiblePermanentGap() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 10_000)
        index.ensureWork(assetId, IndexChannel.VISUAL, AccessRevision, "visual-v1", ComponentHash, 2)

        repeat(3) { zeroBasedAttempt ->
            val attempt = zeroBasedAttempt + 1
            val now = attempt * 100L
            val claim =
                index.claimBatch(
                    "window-a",
                    IndexChannel.VISUAL,
                    "claim-$attempt",
                    now,
                    50,
                    1,
                ).single()
            val state =
                index.recordFailure(
                    leaseToken = checkNotNull(claim.leaseToken),
                    errorCode = "DECODE_FAILED",
                    nextEligibleAtMillis = now + 50,
                    nowMillis = now + 1,
                )
            val expected = if (attempt < 3) IndexWorkState.RETRYABLE_ERROR else IndexWorkState.PERMANENT_ERROR
            assertEquals(expected, state)
            if (attempt < 3) {
                assertTrue(index.claimBatch("window-a", IndexChannel.VISUAL, "too-early-$attempt", now + 49, 50, 1).isEmpty())
            }
        }

        assertTrue(index.claimBatch("window-a", IndexChannel.VISUAL, "after-gap", 1_000, 50, 1).isEmpty())
        assertEquals(3, index.work(assetId, IndexChannel.VISUAL)?.attempt)
        assertEquals(IndexWorkState.PERMANENT_ERROR, index.work(assetId, IndexChannel.VISUAL)?.state)
    }

    @Test
    fun explicitRetryReopensOnlyPermanentGaps() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        index.createOperation(
            operation =
                IndexOperationEntity(
                    operationId = OperationId,
                    profileId = "balanced-v1",
                    targetPackId = "nayti-offline-search",
                    targetPackVersion = "0.1.0-alpha.1",
                    denominatorCatalogRevision = 1,
                    denominatorAssetCount = 1,
                    state = IndexOperationState.PLANNED,
                    autoResume = true,
                    createdAtMillis = 0,
                    updatedAtMillis = 0,
                    completedAtMillis = null,
                ),
            channels =
                listOf(
                    IndexOperationChannelEntity(
                        operationId = OperationId,
                        channel = IndexChannel.VISUAL,
                        priority = 0,
                        pipelineVersion = "visual-v1",
                        componentHash = ComponentHash,
                    ),
                ),
            assets =
                listOf(
                    IndexOperationAssetEntity(
                        operationId = OperationId,
                        assetId = assetId,
                        sourceFingerprint = "source-a",
                    ),
                ),
        )
        index.startExecutionWindow(window("window-a", 0, 10_000), 0)
        index.ensureWork(assetId, IndexChannel.VISUAL, AccessRevision, "visual-v1", ComponentHash, 2)
        repeat(3) { zeroBasedAttempt ->
            val attempt = zeroBasedAttempt + 1
            val now = attempt * 100L
            val claim =
                index.claimBatch(
                    "window-a",
                    IndexChannel.VISUAL,
                    "retryable-$attempt",
                    now,
                    50,
                    1,
                ).single()
            index.recordFailure(
                leaseToken = checkNotNull(claim.leaseToken),
                errorCode = "DECODE_FAILED",
                nextEligibleAtMillis = now + 50,
                nowMillis = now + 1,
            )
        }
        index.stopExecutionWindow("window-a", IndexExecutionWindowState.FINISHED, 500)
        index.refreshOperationTerminalState(OperationId, 501)

        assertEquals(IndexOperationState.COMPLETED_WITH_GAPS, index.operation(OperationId)?.state)
        assertEquals(1, index.retryPermanentGaps(OperationId, 502))
        val retried = checkNotNull(index.work(assetId, IndexChannel.VISUAL))
        assertEquals(IndexWorkState.PENDING, retried.state)
        assertEquals(0, retried.attempt)
        assertNull(retried.errorCode)
        assertEquals(IndexOperationState.PLANNED, index.operation(OperationId)?.state)

        index.startExecutionWindow(window("window-b", 503, 1_000), 503)
        assertEquals(
            assetId,
            index.claimBatch("window-b", IndexChannel.VISUAL, "retried", 504, 50, 1).single().assetId,
        )
    }

    @Test
    fun stoppingWindowInvalidatesOutstandingWork() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 2)
        index.claimBatch("window-a", IndexChannel.OCR, "claim-a", 10, 100, 1).single()

        assertEquals(1, index.stopExecutionWindow("window-a", IndexExecutionWindowState.CANCELLED, 20))

        val work = checkNotNull(index.work(assetId, IndexChannel.OCR))
        assertEquals(IndexWorkState.PENDING, work.state)
        assertNull(work.leaseToken)
        assertNull(work.executionWindowId)
    }

    @Test
    fun userPauseInvalidatesLeaseAndExplicitResumeAllowsAnotherWindow() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 2)
        val stale = index.claimBatch("window-a", IndexChannel.OCR, "claim-a", 10, 100, 1).single()

        val paused = index.transitionOperation(OperationId, IndexOperationState.PAUSED_USER, false, 20)

        assertEquals(IndexOperationState.PAUSED_USER, paused.state)
        assertEquals(IndexExecutionWindowState.CANCELLED, index.executionWindow("window-a")?.state)
        assertEquals(IndexWorkState.PENDING, index.work(assetId, IndexChannel.OCR)?.state)
        assertNull(index.commitSqlPublication(checkNotNull(stale.leaseToken), "late", ResultHash, 1, 21))
        assertTrue(runCatching { index.startExecutionWindow(window("blocked", 22, 500), 22) }.isFailure)

        val resumed = index.transitionOperation(OperationId, IndexOperationState.PLANNED, true, 23)
        assertEquals(IndexOperationState.PLANNED, resumed.state)
        index.startExecutionWindow(window("window-b", 24, 500), 24)
        assertEquals(IndexOperationState.RUNNING, index.operation(OperationId)?.state)
    }

    @Test
    fun stopForNowDisablesAutomaticStartAndCancelIsTerminal() = runBlocking {
        insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)

        val stopped = index.transitionOperation(OperationId, IndexOperationState.WAITING_SYSTEM, false, 20)

        assertEquals(IndexOperationState.WAITING_SYSTEM, stopped.state)
        assertTrue(!stopped.autoResume)
        assertTrue(runCatching { index.startExecutionWindow(window("blocked", 21, 500), 21) }.isFailure)

        val scheduled = index.transitionOperation(OperationId, IndexOperationState.WAITING_SYSTEM, true, 22)
        assertTrue(scheduled.autoResume)
        index.transitionOperation(OperationId, IndexOperationState.PLANNED, true, 23)
        val cancelled = index.transitionOperation(OperationId, IndexOperationState.CANCELLED, false, 24)
        assertEquals(IndexOperationState.CANCELLED, cancelled.state)
        assertEquals(24L, cancelled.completedAtMillis)
        assertTrue(
            runCatching {
                index.transitionOperation(OperationId, IndexOperationState.PLANNED, true, 25)
            }.isFailure,
        )
    }

    @Test
    fun systemStopInvalidatesLeaseAndAllowsAutomaticResume() = runBlocking {
        val assetId = insertAsset("source-a")
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash, 2)
        val stale = index.claimBatch("window-a", IndexChannel.OCR, "claim-a", 10, 100, 1).single()

        val waiting = index.transitionOperation(OperationId, IndexOperationState.WAITING_SYSTEM, true, 20)

        assertEquals(IndexOperationState.WAITING_SYSTEM, waiting.state)
        assertTrue(waiting.autoResume)
        assertEquals(IndexExecutionWindowState.CANCELLED, index.executionWindow("window-a")?.state)
        assertEquals(IndexWorkState.PENDING, index.work(assetId, IndexChannel.OCR)?.state)
        assertNull(index.commitSqlPublication(checkNotNull(stale.leaseToken), "late", ResultHash, 1, 21))
        index.startExecutionWindow(window("window-b", 22, 500), 22)
        assertEquals(IndexOperationState.RUNNING, index.operation(OperationId)?.state)
    }

    @Test
    fun publicationTokenCollisionCannotReplaceAnotherAssetsEvidence() = runBlocking {
        val firstAssetId = insertAsset("source-a", mediaStoreId = 7)
        val secondAssetId = insertAsset("source-b", mediaStoreId = 8)
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        startOperationAndWindow("window-a", expiresAtMillis = 1_000)
        index.ensureWork(firstAssetId, IndexChannel.PHASH, AccessRevision, "phash-v1", ComponentHash, 2)
        index.ensureWork(secondAssetId, IndexChannel.PHASH, AccessRevision, "phash-v1", ComponentHash, 2)
        val claims = index.claimBatch("window-a", IndexChannel.PHASH, "claim-a", 10, 100, 2)
        val first = claims.first { it.assetId == firstAssetId }
        val second = claims.first { it.assetId == secondAssetId }
        val token = "one-publication-token"
        assertTrue(index.commitSqlPublication(checkNotNull(first.leaseToken), token, ResultHash, 8, 20) != null)

        val collision =
            index.commitSqlPublication(checkNotNull(second.leaseToken), token, ResultHash, 8, 21)

        assertNull(collision)
        assertEquals(firstAssetId, index.publication(firstAssetId, IndexChannel.PHASH)?.assetId)
        assertNull(index.publication(secondAssetId, IndexChannel.PHASH))
        assertEquals(IndexWorkState.RUNNING, index.work(secondAssetId, IndexChannel.PHASH)?.state)
    }

    private suspend fun startOperationAndWindow(windowId: String, expiresAtMillis: Long) {
        index.insertOperation(
            IndexOperationEntity(
                operationId = OperationId,
                profileId = "balanced-v1",
                targetPackId = "nayti-offline-search",
                targetPackVersion = "0.1.0-alpha.1",
                denominatorCatalogRevision = 1,
                denominatorAssetCount = 1,
                state = IndexOperationState.PLANNED,
                autoResume = true,
                createdAtMillis = 0,
                updatedAtMillis = 0,
                completedAtMillis = null,
            ),
        )
        index.startExecutionWindow(window(windowId, 0, expiresAtMillis), 0)
    }

    private fun window(windowId: String, startedAtMillis: Long, expiresAtMillis: Long) =
        IndexExecutionWindowEntity(
            windowId = windowId,
            operationId = OperationId,
            hostType = "TEST",
            leaseToken = "$windowId-lease",
            state = IndexExecutionWindowState.RUNNING,
            startedAtMillis = startedAtMillis,
            expiresAtMillis = expiresAtMillis,
            finishedAtMillis = null,
        )

    private suspend fun insertAsset(sourceFingerprint: String, mediaStoreId: Long = 7): Long =
        catalog.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = mediaStoreId,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 10,
                height = 10,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = 1,
                dateTakenMillis = null,
                dateModifiedSeconds = 1,
                displayName = null,
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
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private companion object {
        const val DatabaseName = "index-state-instrumented.db"
        const val OperationId = "operation-a"
        const val AccessRevision = 7L
        const val ComponentHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ResultHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
