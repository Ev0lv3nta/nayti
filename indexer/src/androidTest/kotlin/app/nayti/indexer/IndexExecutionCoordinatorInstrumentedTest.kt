package app.nayti.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexOperationState
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexStateDao
import app.nayti.storage.IndexWorkState
import app.nayti.storage.StorageContract
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexExecutionCoordinatorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var storage: CatalogStorage

    @Before
    fun setUp() {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        storage = CatalogStorage.open(context)
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(StorageContract.DatabaseFileName)
    }

    @Test
    fun processDeathRecoversCapturedOperationWithoutDuplicatePublication() = runBlocking {
        repeat(3) { index -> insertAsset(mediaStoreId = index + 1L) }
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        val firstExecutor = DeterministicExecutor(storage.indexStateDao, clock, crashAtCall = 2)
        val firstCoordinator = coordinator(clock, "first", mapOf(IndexChannel.OCR to firstExecutor))
        val request = request(IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash))
        val operation = firstCoordinator.planOperation(request)
        val firstWindow = firstCoordinator.startExecutionWindow(operation.operationId, "TEST", 90)

        val failure = runCatching { firstCoordinator.runWindow(firstWindow.windowId, itemLimit = 3) }.exceptionOrNull()

        assertTrue(failure is SimulatedProcessDeath)
        assertEquals(1L, countState(IndexWorkState.DONE))
        assertEquals(2L, countState(IndexWorkState.RUNNING))
        storage.close()

        storage = CatalogStorage.open(context)
        clock.nowMillis = 100
        val secondExecutor = DeterministicExecutor(storage.indexStateDao, clock)
        val secondCoordinator = coordinator(clock, "second", mapOf(IndexChannel.OCR to secondExecutor))
        assertEquals(1 to 2, secondCoordinator.recoverExpiredExecution())
        val resumed = secondCoordinator.planOperation(request)
        assertEquals(3L, resumed.denominatorAssetCount)
        assertEquals(3, storage.indexStateDao.operationAssets(resumed.operationId).size)
        val secondWindow = secondCoordinator.startExecutionWindow(resumed.operationId, "TEST", 100)

        val report = secondCoordinator.runWindow(secondWindow.windowId, itemLimit = 3)

        assertEquals(2, report.published)
        assertEquals(3L, countState(IndexWorkState.DONE))
        assertEquals(0L, countState(IndexWorkState.RUNNING))
        repeat(3) { index ->
            assertTrue(storage.indexStateDao.publication(index + 1L, IndexChannel.OCR) != null)
        }
        assertEquals(IndexOperationState.COMPLETED, storage.indexStateDao.operation(OperationId)?.state)
        assertEquals(0L, storage.indexStateDao.operationProgress(OperationId).outstandingCount)
        val coverage = storage.indexStateDao.channelCoverage(IndexChannel.OCR, AccessRevision, "ocr-v1", ComponentHash)
        assertEquals(3L, coverage.accessibleAssetCount)
        assertEquals(3L, coverage.committedAssetCount)
        assertEquals(0L, coverage.outstandingAssetCount)
    }

    @Test
    fun dependencyOrderRunsOcrBeforeSemanticForEveryAsset() = runBlocking {
        repeat(2) { index -> insertAsset(mediaStoreId = index + 1L) }
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        val order = mutableListOf<String>()
        val executors =
            mapOf(
                IndexChannel.OCR to DeterministicExecutor(storage.indexStateDao, clock, order = order),
                IndexChannel.OCR_SEMANTIC to DeterministicExecutor(storage.indexStateDao, clock, order = order),
            )
        val coordinator = coordinator(clock, "ordered", executors)
        val operation =
            coordinator.planOperation(
                request(
                    IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash),
                    IndexChannelContract(IndexChannel.OCR_SEMANTIC, 1, "semantic-v1", OtherComponentHash),
                ),
            )
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)

        val report = coordinator.runWindow(window.windowId, itemLimit = 4)

        assertEquals(4, report.published)
        assertEquals(
            listOf(IndexChannel.OCR, IndexChannel.OCR, IndexChannel.OCR_SEMANTIC, IndexChannel.OCR_SEMANTIC),
            order,
        )
        assertEquals(IndexOperationState.COMPLETED, storage.indexStateDao.operation(OperationId)?.state)
    }

    @Test
    fun oneOperationCanRunInSeparateModelBoundChannelWindows() = runBlocking {
        insertAsset(mediaStoreId = 1)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        val request =
            request(
                IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash),
                IndexChannelContract(IndexChannel.OCR_SEMANTIC, 1, "semantic-v1", ComponentHash),
            )
        val operation = coordinator(clock, "planner", emptyMap()).planOperation(request)

        val ocr = DeterministicExecutor(storage.indexStateDao, clock)
        val ocrCoordinator = coordinator(clock, "ocr-phase", mapOf(IndexChannel.OCR to ocr))
        val ocrWindow = ocrCoordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)
        val ocrReport =
            ocrCoordinator.runWindow(
                ocrWindow.windowId,
                itemLimit = 1,
                channelsToRun = setOf(IndexChannel.OCR),
            )
        assertEquals(1, ocrReport.published)
        assertEquals(IndexWorkState.PENDING, storage.indexStateDao.work(1, IndexChannel.OCR_SEMANTIC)?.state)

        val semantic = DeterministicExecutor(storage.indexStateDao, clock)
        val semanticCoordinator =
            coordinator(clock, "semantic-phase", mapOf(IndexChannel.OCR_SEMANTIC to semantic))
        val semanticWindow = semanticCoordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)
        val semanticReport =
            semanticCoordinator.runWindow(
                semanticWindow.windowId,
                itemLimit = 1,
                channelsToRun = setOf(IndexChannel.OCR_SEMANTIC),
            )

        assertEquals(1, semanticReport.published)
        assertEquals(IndexOperationState.COMPLETED, storage.indexStateDao.operation(OperationId)?.state)
    }

    @Test
    fun permanentItemFailureCompletesWithVisibleGapAndLedgerEvidence() = runBlocking {
        insertAsset(mediaStoreId = 1)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        val coordinator = coordinator(
            clock,
            "gap",
            mapOf(IndexChannel.VISUAL to IndexChannelExecutor { IndexExecutionOutcome.Permanent("DECODE_FAILED") }),
        )
        val operation = coordinator.planOperation(
            request(IndexChannelContract(IndexChannel.VISUAL, 0, "visual-v1", ComponentHash)),
        )
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.permanentFailures)
        assertEquals(IndexOperationState.COMPLETED_WITH_GAPS, storage.indexStateDao.operation(OperationId)?.state)
        val progress = storage.indexStateDao.operationProgress(OperationId)
        assertEquals(1L, progress.plannedCount)
        assertEquals(0L, progress.committedCount)
        assertEquals(1L, progress.permanentGapCount)
        assertEquals(0L, progress.outstandingCount)
        val error = storage.indexStateDao.ledgerError("item:$OperationId:1:${IndexChannel.VISUAL}:DECODE_FAILED")
        assertEquals("DECODE_FAILED", error?.code)
        assertEquals(false, error?.retryable)
        assertNull(error?.resolvedAtMillis)
        val coverage = storage.indexStateDao.channelCoverage(IndexChannel.VISUAL, AccessRevision, "visual-v1", ComponentHash)
        assertEquals(1L, coverage.accessibleAssetCount)
        assertEquals(0L, coverage.committedAssetCount)
        assertEquals(1L, coverage.permanentGapCount)
        assertEquals(0L, coverage.outstandingAssetCount)
    }

    @Test
    fun planningIsExecutorIndependentButExecutionRejectsMissingChannelBeforeClaims() = runBlocking {
        insertAsset(mediaStoreId = 1)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val coordinator = coordinator(MutableClock(10), "missing", emptyMap())
        val request = request(IndexChannelContract(IndexChannel.VISUAL, 0, "visual-v1", ComponentHash))
        val operation = coordinator.planOperation(request)
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)

        val failure = runCatching { coordinator.runWindow(window.windowId) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(IndexWorkState.PENDING, storage.indexStateDao.work(1, IndexChannel.VISUAL)?.state)
        assertTrue(storage.indexStateDao.publication(1, IndexChannel.VISUAL) == null)
    }

    @Test
    fun executionControlStopsBeforeAnotherItemAndReleasesPrefetchedClaims() = runBlocking {
        repeat(3) { index -> insertAsset(mediaStoreId = index + 1L) }
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        var continueExecution = true
        val delegate = DeterministicExecutor(storage.indexStateDao, clock)
        val executor =
            IndexChannelExecutor { claim ->
                delegate.execute(claim).also { continueExecution = false }
            }
        val coordinator = coordinator(clock, "controlled", mapOf(IndexChannel.OCR to executor))
        val operation = coordinator.planOperation(
            request(IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash)),
        )
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)

        val report = coordinator.runWindow(
            windowId = window.windowId,
            itemLimit = 3,
            control = IndexExecutionControl { continueExecution },
        )

        assertEquals(3, report.claimed)
        assertEquals(1, report.published)
        assertEquals(1L, countState(IndexWorkState.DONE))
        assertEquals(2L, countState(IndexWorkState.PENDING))
        assertEquals(0L, countState(IndexWorkState.RUNNING))
        assertEquals(IndexOperationState.RUNNING, storage.indexStateDao.operation(OperationId)?.state)
    }

    @Test
    fun coroutineCancellationInvalidatesWindowAndClaimImmediately() = runBlocking {
        insertAsset(mediaStoreId = 1)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(10)
        val coordinator = coordinator(
            clock,
            "cancelled",
            mapOf(IndexChannel.OCR to IndexChannelExecutor { throw CancellationException("stop") }),
        )
        val operation = coordinator.planOperation(
            request(IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash)),
        )
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 1_000)

        val failure = runCatching { coordinator.runWindow(window.windowId) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0L, countState(IndexWorkState.RUNNING))
        assertEquals(1L, countState(IndexWorkState.PENDING))
        assertEquals("CANCELLED", storage.indexStateDao.executionWindow(window.windowId)?.state)
    }

    @Test
    fun scopedOperationCannotClaimPendingWorkFromAnOlderBroaderOperation() = runBlocking {
        insertAsset(mediaStoreId = 1, dateTakenMillis = 1_000)
        insertAsset(mediaStoreId = 2, dateTakenMillis = 10_000)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, 1)
        val clock = MutableClock(20_000)
        val planner = coordinator(clock, "planner", emptyMap())
        val channel = IndexChannelContract(IndexChannel.OCR, 0, "ocr-v1", ComponentHash)
        val broad = planner.planOperation(request(channel, operationId = "operation-all"))
        assertEquals(2L, broad.denominatorAssetCount)

        storage.catalogDao.updateIndexingScope(IndexingScopeMode.SINCE_DATE, 5_000, clock.nowMillis)
        val scoped = planner.planOperation(request(channel, operationId = "operation-scoped"))
        assertEquals(1L, scoped.denominatorAssetCount)
        assertEquals(listOf(2L), storage.indexStateDao.operationAssets(scoped.operationId).map { it.assetId })

        val executor = DeterministicExecutor(storage.indexStateDao, clock)
        val runner = coordinator(clock, "scoped", mapOf(IndexChannel.OCR to executor))
        val window = runner.startExecutionWindow(scoped.operationId, "TEST", 1_000)
        val report = runner.runWindow(window.windowId, itemLimit = 2)

        assertEquals(1, report.published)
        assertEquals(IndexWorkState.PENDING, storage.indexStateDao.work(1, IndexChannel.OCR)?.state)
        assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(2, IndexChannel.OCR)?.state)
        assertEquals(IndexOperationState.COMPLETED, storage.indexStateDao.operation(scoped.operationId)?.state)
    }

    private fun coordinator(
        clock: MutableClock,
        prefix: String,
        executors: Map<String, IndexChannelExecutor>,
    ) =
        IndexExecutionCoordinator(
            indexState = storage.indexStateDao,
            catalog = storage.catalogDao,
            executors = executors,
            clock = clock,
            ids = SequentialIdFactory(prefix),
        )

    private fun request(
        vararg channels: IndexChannelContract,
        operationId: String = OperationId,
    ) =
        IndexOperationRequest(
            operationId = operationId,
            profileId = "balanced-v1",
            targetPackId = "nayti-offline-search",
            targetPackVersion = "0.1.0-alpha.1",
            channels = channels.toList(),
            autoResume = true,
        )

    private suspend fun insertAsset(
        mediaStoreId: Long,
        dateTakenMillis: Long? = null,
    ) {
        storage.catalogDao.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = mediaStoreId,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 10,
                height = 10,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = mediaStoreId,
                dateTakenMillis = dateTakenMillis,
                dateModifiedSeconds = mediaStoreId,
                displayName = null,
                bucketId = null,
                bucketDisplayName = null,
                relativePath = null,
                sourceFingerprint = "source-$mediaStoreId",
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = 1,
            ),
        )
    }

    private suspend fun countState(state: String): Long =
        storage.indexStateDao.workStateCounts().firstOrNull { count -> count.state == state }?.count ?: 0

    private class DeterministicExecutor(
        private val indexState: IndexStateDao,
        private val clock: MutableClock,
        private val crashAtCall: Int? = null,
        private val order: MutableList<String>? = null,
    ) : IndexChannelExecutor {
        private var calls = 0

        override suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome {
            calls += 1
            if (calls == crashAtCall) throw SimulatedProcessDeath()
            order?.add(claim.work.channel)
            val bytes =
                "${claim.work.assetId}|${claim.work.channel}|${claim.work.sourceFingerprint}|" +
                    "${claim.work.pipelineVersion}|${claim.work.componentHash}"
            val publication =
                indexState.commitSqlPublication(
                    leaseToken = checkNotNull(claim.work.leaseToken),
                    publicationToken = claim.publicationToken,
                    resultSha256 = sha256(bytes.encodeToByteArray()),
                    resultLength = bytes.encodeToByteArray().size.toLong(),
                    nowMillis = clock.nowMillis,
                )
            return if (publication == null) IndexExecutionOutcome.LeaseRejected else IndexExecutionOutcome.Published
        }
    }

    private class MutableClock(var nowMillis: Long) : IndexCoordinatorClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class SequentialIdFactory(private val prefix: String) : IndexIdFactory {
        private var sequence = 0

        override fun create(purpose: String): String = "$prefix-$purpose-${++sequence}"
    }

    private class SimulatedProcessDeath : RuntimeException()

    private companion object {
        const val OperationId = "operation-a"
        const val AccessRevision = 7L
        const val ComponentHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OtherComponentHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}
