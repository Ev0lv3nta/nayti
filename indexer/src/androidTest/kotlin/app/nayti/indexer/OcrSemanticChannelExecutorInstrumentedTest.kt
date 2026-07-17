package app.nayti.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexWorkState
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.OcrDocumentDraft
import app.nayti.storage.OcrPublicationCodec
import app.nayti.storage.OcrRegionDraft
import app.nayti.storage.StorageContract
import app.nayti.storage.VectorGenerationEntity
import app.nayti.storage.VectorGenerationState
import app.nayti.search.engine.fusion.TextFusionReason
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrSemanticChannelExecutorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vectorRoot: File = context.filesDir.resolve("semantic-executor-test")
    private lateinit var storage: CatalogStorage
    private var now = 10_000L

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(vectorRoot.deleteRecursively())
        storage = CatalogStorage.open(context)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, now)
        storage.catalogDao.insertAsset(asset())
        storage.modelPackDao.registerInstalledCandidate(pack())
        storage.vectorIndexDao.createGeneration(generation())
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(vectorRoot.deleteRecursively())
    }

    @Test
    fun currentOcrPublishesImmutableChunksVectorsAndSnapshot() = runBlocking {
        publishOcr(listOf("Quarterly report", "Revenue rose in Europe"))
        val engine = FixedEmbeddingEngine()
        val coordinator = coordinator(engine)
        val operation = coordinator.planOperation(request("vectors"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.published)
        assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(AssetId, IndexChannel.OCR_SEMANTIC)?.state)
        val activeId = checkNotNull(storage.vectorIndexDao.activeSnapshotId())
        val snapshot = checkNotNull(storage.vectorIndexDao.snapshot(activeId))
        val manifest = checkNotNull(storage.vectorIndexDao.manifest(checkNotNull(snapshot.semanticManifestRevision)))
        val segment = checkNotNull(storage.vectorIndexDao.segment(storage.vectorIndexDao.manifestSegments(manifest.revision).single().segmentSha256))
        val records = storage.vectorIndexDao.segmentRecords(segment.sha256)
        assertEquals(2, records.size)
        assertTrue(records.all { record -> record.semanticChunkId != null })
        assertTrue(records.all { record -> storage.ocrSemanticDao.chunk(checkNotNull(record.semanticChunkId)) != null })
        assertEquals(
            records.map { it.recordId }.sorted(),
            storage.vectorIndexDao.currentEligibleSemanticRecordIds(
                manifestRevision = manifest.revision,
                segmentSha256 = segment.sha256,
                semanticPipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                componentHash = ComponentHash,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            ),
        )
        assertEquals(2, engine.encodedTexts.size)
        assertEquals(1L, snapshot.lexicalPublicationEpoch)

        val evidence =
            storage.vectorIndexDao.currentSemanticEvidence(
                manifestRevision = manifest.revision,
                recordIds = records.map { record -> record.recordId },
                semanticPipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                componentHash = ComponentHash,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            )
        assertEquals(records.map { it.recordId }.sorted(), evidence.map { it.recordId }.sorted())
        val search =
            OcrSemanticSearch(
                vectors = storage.vectorIndexDao,
                semantic = storage.ocrSemanticDao,
                vectorRoot = vectorRoot,
                sessions = SemanticQuerySessionFactory { contract -> FixedQuerySession(contract) },
                clock = { now },
                leaseTokens = { "semantic-query-test" },
            )
        val searchResult = search.search("European revenue")
        assertEquals(OcrSemanticSearchStatus.READY, searchResult.status)
        assertEquals(snapshot.snapshotId, searchResult.snapshotId)
        assertEquals(1, searchResult.hits.size)
        assertEquals(AssetId, searchResult.hits.single().assetId)
        assertTrue(searchResult.hits.single().matchedLineOrdinals.isNotEmpty())
        assertNull(storage.vectorIndexDao.queryLease("semantic-query-test"))
        val hybrid = OcrHybridSearch(storage.ocrDao, storage.vectorIndexDao, search)
        val hybridResult =
            hybrid.search(
                query = "financial growth",
                pipelineVersion = OcrPipelineVersion,
                fallbackComponentHash = ComponentHash,
            )
        assertEquals(OcrSemanticSearchStatus.READY, hybridResult.semanticStatus)
        assertEquals(TextFusionReason.SEMANTIC_TEXT, hybridResult.hits.single().evidence)
        assertEquals(AssetId, hybridResult.hits.single().assetId)

        storage.vectorIndexDao.createGeneration(
            generation().copy(generationId = CompactionGenerationId),
        )
        records.forEachIndexed { index, record ->
            val lease = "semantic-compaction-lease-$index"
            storage.indexStateDao.replaceWork(
                runningWork(
                    IndexChannel.OCR_SEMANTIC,
                    OcrSemanticChannelExecutor.PipelineVersion,
                    lease,
                ),
            )
            val activeForPublication = checkNotNull(storage.vectorIndexDao.activeSnapshotId()).let { id ->
                checkNotNull(storage.vectorIndexDao.snapshot(id))
            }
            VectorPublicationStore(vectorRoot, storage.vectorIndexDao, nowMillis = { now }).publish(
                VectorPublicationRequest(
                    publicationToken = "semantic-compaction-input-$index",
                    generationId = CompactionGenerationId,
                    manifestRevision = "semantic-compaction-input-manifest-$index",
                    snapshotId = "semantic-compaction-input-snapshot-$index",
                    leaseTokens = listOf(lease),
                    records =
                        listOf(
                            PublishedVectorRecord(
                                recordId = record.recordId,
                                assetId = record.assetId,
                                chunkOrdinal = record.chunkOrdinal,
                                sourceFingerprint = record.sourceFingerprint,
                                vector = engine.encodeDocument("compaction input $index"),
                                semanticChunkId = record.semanticChunkId,
                            ),
                        ),
                    rankingConfigVersion = activeForPublication.rankingConfigVersion,
                    lexicalPublicationEpoch = activeForPublication.lexicalPublicationEpoch,
                    pHashPublicationEpoch = activeForPublication.pHashPublicationEpoch,
                    catalogWatermark = activeForPublication.catalogWatermark,
                ),
            )
        }
        val beforeCompaction = checkNotNull(storage.vectorIndexDao.activeSnapshotId()).let { id ->
            checkNotNull(storage.vectorIndexDao.snapshot(id))
        }
        val compacted =
            VectorCompactionStore(vectorRoot, storage.vectorIndexDao, nowMillis = { now }).compact(
                VectorCompactionRequest(
                    compactionToken = "semantic-compaction",
                    generationId = CompactionGenerationId,
                    firstSegmentOrdinal = 0,
                    segmentCount = 2,
                    manifestRevision = "semantic-compacted-manifest",
                    snapshotId = "semantic-compacted-snapshot",
                    rankingConfigVersion = beforeCompaction.rankingConfigVersion,
                    lexicalPublicationEpoch = beforeCompaction.lexicalPublicationEpoch,
                    pHashPublicationEpoch = beforeCompaction.pHashPublicationEpoch,
                    catalogWatermark = beforeCompaction.catalogWatermark,
                    segmentId = UUID.nameUUIDFromBytes("semantic-compacted-segment".encodeToByteArray()),
                ),
            )
        val compactedManifest = checkNotNull(compacted.semanticManifestRevision).let { revision ->
            checkNotNull(storage.vectorIndexDao.manifest(revision))
        }
        val compactedSegment = storage.vectorIndexDao.manifestSegments(compactedManifest.revision).single().let { entry ->
            checkNotNull(storage.vectorIndexDao.segment(entry.segmentSha256))
        }
        val compactedRecords = storage.vectorIndexDao.segmentRecords(compactedSegment.sha256)
        assertEquals(1, compactedSegment.compactionLevel)
        assertEquals(2, compactedRecords.size)
        assertTrue(compactedRecords.all { it.semanticChunkId != null })
        assertEquals(
            OcrSemanticSearchStatus.READY,
            search.search("European revenue").status,
        )

        storage.catalogDao.recordAccessObservation("Full", AccessRevision + 1, now + 1)
        assertTrue(
            storage.vectorIndexDao.currentEligibleSemanticRecordIds(
                manifestRevision = manifest.revision,
                segmentSha256 = segment.sha256,
                semanticPipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                componentHash = ComponentHash,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            ).isEmpty(),
        )
        assertTrue(
            storage.vectorIndexDao.currentSemanticEvidence(
                manifestRevision = manifest.revision,
                recordIds = records.map { record -> record.recordId },
                semanticPipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                componentHash = ComponentHash,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            ).isEmpty(),
        )
    }

    @Test
    fun lowSignalOcrCommitsDurableEmptyResultWithoutVectorArtifact() = runBlocking {
        publishOcr(listOf("x 1 !"))
        val engine = FixedEmbeddingEngine()
        val coordinator = coordinator(engine)
        val operation = coordinator.planOperation(request("empty"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.published)
        assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(AssetId, IndexChannel.OCR_SEMANTIC)?.state)
        assertTrue(storage.indexStateDao.publication(AssetId, IndexChannel.OCR_SEMANTIC)?.resultLength!! > 0)
        assertTrue(engine.encodedTexts.isEmpty())
        assertNull(storage.vectorIndexDao.activeSnapshotId())
        assertTrue(vectorRoot.resolve("segments").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun permanentOcrGapTerminatesDependentSemanticWork() = runBlocking {
        storage.indexStateDao.replaceWork(
            runningWork(IndexChannel.OCR, OcrPipelineVersion, "failed-ocr").copy(
                state = IndexWorkState.PERMANENT_ERROR,
                leaseToken = null,
                leaseExpiresAtMillis = null,
                errorCode = "OCR_CORRUPT_MEDIA",
            ),
        )
        val coordinator = coordinator(FixedEmbeddingEngine())
        val operation = coordinator.planOperation(request("ocr-gap"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.permanentFailures)
        val semanticWork = checkNotNull(storage.indexStateDao.work(AssetId, IndexChannel.OCR_SEMANTIC))
        assertEquals(IndexWorkState.PERMANENT_ERROR, semanticWork.state)
        assertEquals(OcrSemanticChannelExecutor.ErrorOcrDependency, semanticWork.errorCode)
        assertNull(storage.vectorIndexDao.activeSnapshotId())
    }

    private fun coordinator(engine: SemanticEmbeddingEngine): IndexExecutionCoordinator {
        val executor =
            OcrSemanticChannelExecutor(
                indexState = storage.indexStateDao,
                semantic = storage.ocrSemanticDao,
                embedding = engine,
                publisher =
                    VectorStoreSemanticPublisher(
                        VectorPublicationStore(vectorRoot, storage.vectorIndexDao, nowMillis = { now }),
                    ),
                generationId = GenerationId,
                clock = OcrExecutorClock { now },
            )
        return IndexExecutionCoordinator(
            indexState = storage.indexStateDao,
            catalog = storage.catalogDao,
            executors = mapOf(IndexChannel.OCR_SEMANTIC to executor),
            clock = IndexCoordinatorClock { now },
            ids = SequentialIds(),
        )
    }

    private suspend fun publishOcr(lines: List<String>) {
        val lease = "ocr-lease"
        storage.indexStateDao.replaceWork(runningWork(IndexChannel.OCR, OcrPipelineVersion, lease))
        val regions = lines.map(::region)
        val document =
            OcrDocumentDraft(
                assetId = AssetId,
                sourceFingerprint = Fingerprint,
                accessRevision = AccessRevision,
                pipelineVersion = OcrPipelineVersion,
                componentHash = ComponentHash,
                sourceWidth = 100,
                sourceHeight = 100,
                rawText = lines.joinToString("\n"),
                displayText = lines.joinToString("\n"),
                canonicalText = lines.joinToString("\n").lowercase(),
                stemText = lines.joinToString(" ").lowercase(),
                identifierText = "",
                normalizerVersion = "unicode-search-v1",
                stemmerVersion = "ru-en-light-v1",
                identifierVersion = "identifier-v1",
            )
        val publication =
            storage.ocrDao.commitOcrPublication(
                leaseToken = lease,
                publicationToken = "ocr-publication",
                expectedIdentity = OcrPublicationCodec.identity(document, regions),
                document = document,
                regions = regions,
                nowMillis = now,
            )
        checkNotNull(publication)
    }

    private fun request(suffix: String) =
        IndexOperationRequest(
            operationId = "semantic-executor-$suffix",
            profileId = "balanced-v1",
            targetPackId = PackId,
            targetPackVersion = PackVersion,
            channels =
                listOf(
                    IndexChannelContract(
                        IndexChannel.OCR_SEMANTIC,
                        0,
                        OcrSemanticChannelExecutor.PipelineVersion,
                        ComponentHash,
                    ),
                ),
            autoResume = true,
        )

    private fun runningWork(channel: String, pipelineVersion: String, lease: String) =
        IndexChannelWorkEntity(
            assetId = AssetId,
            channel = channel,
            state = IndexWorkState.RUNNING,
            sourceFingerprint = Fingerprint,
            accessRevision = AccessRevision,
            pipelineVersion = pipelineVersion,
            componentHash = ComponentHash,
            attempt = 1,
            leaseToken = lease,
            leaseExpiresAtMillis = now + 60_000,
            executionWindowId = null,
            publicationToken = null,
            stagedArtifactPath = null,
            stagedArtifactLength = null,
            stagedArtifactSha256 = null,
            nextEligibleAtMillis = null,
            errorCode = null,
            updatedAtMillis = now,
        )

    private fun region(text: String) =
        OcrRegionDraft(
            rawText = text,
            displayText = text,
            canonicalText = text.lowercase(),
            confidenceMicros = 900_000,
            x0Micros = 0,
            y0Micros = 0,
            x1Micros = 1_000_000,
            y1Micros = 0,
            x2Micros = 1_000_000,
            y2Micros = 1_000_000,
            x3Micros = 0,
            y3Micros = 1_000_000,
        )

    private fun asset() =
        CatalogAssetEntity(
            assetId = AssetId,
            volumeName = "external_primary",
            mediaStoreId = 1,
            mimeType = "image/jpeg",
            sizeBytes = 100,
            width = 100,
            height = 100,
            orientationDegrees = 0,
            generationAdded = 1,
            generationModified = 1,
            dateTakenMillis = null,
            dateModifiedSeconds = 1,
            displayName = null,
            bucketId = null,
            bucketDisplayName = null,
            relativePath = null,
            sourceFingerprint = Fingerprint,
            availability = CatalogAvailability.AVAILABLE,
            lastSeenInventoryRunId = 1,
            missingFullObservationCount = 0,
            quarantineStartedAtMillis = null,
            sourceObservedAtMillis = now,
        )

    private fun pack() =
        ModelPackEntity(
            packId = PackId,
            packVersion = PackVersion,
            keyId = "test-key",
            manifestSha256 = ComponentHash,
            relativeDirectory = "model-packs/test",
            payloadBytes = 1,
            installedAtMillis = now,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private fun generation() =
        VectorGenerationEntity(
            generationId = GenerationId,
            channel = IndexChannel.OCR_SEMANTIC,
            packId = PackId,
            packVersion = PackVersion,
            pipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
            componentHash = ComponentHash,
            embeddingSpaceHash = EmbeddingHash,
            dimension = Dimension,
            state = VectorGenerationState.BUILDING,
            createdAtMillis = now,
            sealedAtMillis = null,
        )

    private class FixedEmbeddingEngine : SemanticEmbeddingEngine {
        val encodedTexts = mutableListOf<String>()

        override fun contentTokenCount(text: String): Int = Regex("\\S+").findAll(text).count()

        override fun encodeDocument(text: String): ByteArray {
            encodedTexts += text
            return ByteArray(Dimension) { index -> ((text.length + index) % 127 + 1).toByte() }
        }
    }

    private class FixedQuerySession(
        contract: SemanticQueryContract,
    ) : SemanticQuerySession {
        override val embeddingSpaceHash: String = contract.embeddingSpaceHash
        override val dimension: Int = contract.dimension

        override fun encodeQuery(text: String): ByteArray = ByteArray(dimension) { 1 }

        override fun close() = Unit
    }

    private class SequentialIds : IndexIdFactory {
        private var next = 0

        override fun create(purpose: String): String = "$purpose-${++next}"
    }

    private companion object {
        const val AssetId = 1L
        const val AccessRevision = 7L
        const val PackId = "nayti-offline-search"
        const val PackVersion = "0.1.0-alpha.2"
        const val OcrPipelineVersion = "ocr-v1"
        const val GenerationId = "semantic-generation-1"
        const val CompactionGenerationId = "semantic-generation-compaction"
        const val Dimension = 384
        const val Fingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ComponentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val EmbeddingHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
