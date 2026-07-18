package app.nayti.indexer

import app.nayti.ml.runtime.semantic.SemanticRuntimeException
import app.nayti.ml.runtime.semantic.User2OrtRuntime
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexStateDao
import app.nayti.storage.IndexWorkState
import app.nayti.storage.OcrSemanticDao
import java.lang.Long.parseUnsignedLong

interface SemanticEmbeddingEngine {
    fun contentTokenCount(text: String): Int

    fun encodeDocument(text: String): ByteArray
}

class User2SemanticEmbeddingEngine(
    private val runtime: User2OrtRuntime,
) : SemanticEmbeddingEngine {
    override fun contentTokenCount(text: String): Int = runtime.contentTokenCount(text)

    override fun encodeDocument(text: String): ByteArray = runtime.encodeDocument(text).quantized
}

enum class SemanticVectorPublicationResult {
    PUBLISHED,
    LEASE_REJECTED,
}

fun interface SemanticVectorPublisher {
    suspend fun publish(request: VectorPublicationRequest): SemanticVectorPublicationResult
}

class VectorStoreSemanticPublisher(
    private val store: VectorPublicationStore,
) : SemanticVectorPublisher {
    override suspend fun publish(request: VectorPublicationRequest): SemanticVectorPublicationResult =
        try {
            store.publish(request)
            SemanticVectorPublicationResult.PUBLISHED
        } catch (_: VectorPublicationLeaseRejectedException) {
            SemanticVectorPublicationResult.LEASE_REJECTED
        }
}

/** Publishes one current OCR document as immutable USER2 semantic chunks and vectors. */
class OcrSemanticChannelExecutor(
    private val indexState: IndexStateDao,
    private val semantic: OcrSemanticDao,
    private val embedding: SemanticEmbeddingEngine,
    private val publisher: SemanticVectorPublisher,
    private val generationId: String,
    private val clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
) : IndexChannelExecutor {
    private val materializer =
        OcrSemanticChunkMaterializer(
            OcrSemanticChunkPlanner(SemanticTokenCounter(embedding::contentTokenCount)),
        )

    override suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome {
        check(claim.work.channel == IndexChannel.OCR_SEMANTIC)
        val leaseToken = checkNotNull(claim.work.leaseToken)
        val liveWork = indexState.workByLease(leaseToken) ?: return IndexExecutionOutcome.LeaseRejected
        if (liveWork != claim.work) return IndexExecutionOutcome.LeaseRejected
        val ocrDependency = indexState.work(claim.work.assetId, IndexChannel.OCR)
            ?: return IndexExecutionOutcome.LeaseRejected
        if (ocrDependency.state == IndexWorkState.PERMANENT_ERROR) {
            return IndexExecutionOutcome.Permanent(ErrorOcrDependency)
        }
        if (
            ocrDependency.state != IndexWorkState.DONE ||
            ocrDependency.sourceFingerprint != claim.work.sourceFingerprint ||
            ocrDependency.accessRevision != claim.work.accessRevision
        ) {
            return IndexExecutionOutcome.LeaseRejected
        }

        val asset = indexState.catalogAsset(claim.work.assetId) ?: return IndexExecutionOutcome.LeaseRejected
        val access = indexState.currentAccessObservation() ?: return IndexExecutionOutcome.LeaseRejected
        if (
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != claim.work.sourceFingerprint ||
            access.processAccessRevision != claim.work.accessRevision ||
            access.accessScope == "None"
        ) {
            return IndexExecutionOutcome.LeaseRejected
        }

        val ocrPublicationToken = ocrDependency.publicationToken ?: return IndexExecutionOutcome.LeaseRejected
        val document = semantic.ocrDocument(ocrPublicationToken) ?: return IndexExecutionOutcome.LeaseRejected
        if (
            document.assetId != claim.work.assetId ||
            document.sourceFingerprint != claim.work.sourceFingerprint ||
            document.accessRevision != claim.work.accessRevision ||
            document.pipelineVersion != ocrDependency.pipelineVersion ||
            document.componentHash != ocrDependency.componentHash
        ) {
            return IndexExecutionOutcome.LeaseRejected
        }
        val regions = semantic.ocrRegions(document.publicationEpoch)
        val materialization =
            try {
                materializer.materialize(document, regions, clock.nowMillis())
            } catch (_: SemanticRuntimeException) {
                return IndexExecutionOutcome.Retryable(ErrorRuntime)
            }
        val chunkSet = semantic.publishChunkSet(materialization)
            ?: return IndexExecutionOutcome.LeaseRejected

        if (materialization.chunks.isEmpty()) {
            val publication =
                indexState.commitSqlPublication(
                    leaseToken = leaseToken,
                    publicationToken = claim.publicationToken,
                    resultSha256 = chunkSet.payloadSha256,
                    resultLength = chunkSet.payloadByteLength,
                    nowMillis = clock.nowMillis(),
                )
            return if (publication == null) {
                IndexExecutionOutcome.LeaseRejected
            } else {
                IndexExecutionOutcome.Published
            }
        }

        val records =
            try {
                materialization.chunks.map { chunk ->
                    PublishedVectorRecord(
                        recordId = stableRecordId(chunk.chunkId),
                        assetId = chunk.assetId,
                        chunkOrdinal = chunk.ordinal,
                        sourceFingerprint = chunk.sourceFingerprint,
                        accessRevision = claim.work.accessRevision,
                        vector = embedding.encodeDocument(chunk.displayText),
                        semanticChunkId = chunk.chunkId,
                    )
                }
            } catch (_: SemanticRuntimeException) {
                return IndexExecutionOutcome.Retryable(ErrorRuntime)
            }
        check(records.map(PublishedVectorRecord::recordId).distinct().size == records.size)

        val result =
            publisher.publish(
                VectorPublicationRequest(
                    publicationToken = claim.publicationToken,
                    generationId = generationId,
                    manifestRevision = "semantic-manifest-${claim.publicationToken}",
                    snapshotId = "semantic-snapshot-${claim.publicationToken}",
                    leaseTokens = listOf(leaseToken),
                    records = records,
                    rankingConfigVersion = RankingConfigVersion,
                    lexicalPublicationEpoch = semantic.maximumOcrPublicationEpoch(),
                    pHashPublicationEpoch = 0,
                    catalogWatermark = semantic.catalogRevision(),
                ),
            )
        return when (result) {
            SemanticVectorPublicationResult.PUBLISHED -> IndexExecutionOutcome.Published
            SemanticVectorPublicationResult.LEASE_REJECTED -> IndexExecutionOutcome.LeaseRejected
        }
    }

    private fun stableRecordId(chunkId: String): Long {
        require(Sha256.matches(chunkId))
        return (parseUnsignedLong(chunkId.take(16), 16) and Long.MAX_VALUE).takeIf { it > 0 } ?: 1
    }

    companion object {
        const val PipelineVersion = "ocr-semantic-v1"
        const val RankingConfigVersion = "ranking-v1"
        const val ErrorRuntime = "OCR_SEMANTIC_RUNTIME"
        const val ErrorOcrDependency = "OCR_SEMANTIC_OCR_GAP"
        private val Sha256 = Regex("[0-9a-f]{64}")
    }
}
