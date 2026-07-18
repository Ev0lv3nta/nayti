package app.nayti.indexer

import android.graphics.Bitmap
import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.ml.runtime.visual.Siglip2ImageOrtRuntime
import app.nayti.ml.runtime.visual.VisualRuntimeException
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.MediaDecodeAccessException
import app.nayti.platform.media.MediaDecodeContentException
import app.nayti.platform.media.MediaDecodeIoException
import app.nayti.platform.media.MediaKey
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexStateDao
import app.nayti.storage.OcrSemanticDao
import app.nayti.storage.PerceptualHashDao
import app.nayti.storage.VectorIndexDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface VisualEmbeddingEngine {
    fun encodeImage(bitmap: Bitmap): ByteArray
}

class Siglip2VisualEmbeddingEngine(
    private val runtime: Siglip2ImageOrtRuntime,
) : VisualEmbeddingEngine {
    override fun encodeImage(bitmap: Bitmap): ByteArray = runtime.encode(bitmap).quantized
}

enum class VisualVectorPublicationResult {
    PUBLISHED,
    LEASE_REJECTED,
}

fun interface VisualVectorPublisher {
    suspend fun publish(request: VectorPublicationRequest): VisualVectorPublicationResult
}

class VectorStoreVisualPublisher(
    private val store: VectorPublicationStore,
) : VisualVectorPublisher {
    override suspend fun publish(request: VectorPublicationRequest): VisualVectorPublicationResult =
        try {
            store.publish(request)
            VisualVectorPublicationResult.PUBLISHED
        } catch (_: VectorPublicationLeaseRejectedException) {
            VisualVectorPublicationResult.LEASE_REJECTED
        }
}

class ShadowVectorStoreVisualPublisher(
    private val store: VectorPublicationStore,
    private val vectors: VectorIndexDao,
    private val candidateSnapshotId: String,
) : VisualVectorPublisher {
    override suspend fun publish(request: VectorPublicationRequest): VisualVectorPublicationResult =
        try {
            val parent = vectors.latestCompletedPublication(candidateSnapshotId, IndexChannel.VISUAL)
            store.publishShadow(
                request =
                    request.copy(
                        manifestRevision = "candidate-visual-${request.publicationToken}",
                        snapshotId = candidateSnapshotId,
                    ),
                parentManifestRevision = parent?.manifestRevision,
            )
            VisualVectorPublicationResult.PUBLISHED
        } catch (_: VectorPublicationLeaseRejectedException) {
            VisualVectorPublicationResult.LEASE_REJECTED
        }
}

/** Publishes exactly one current MediaStore asset into the immutable SigLIP2 visual index. */
class VisualChannelExecutor(
    private val indexState: IndexStateDao,
    private val semantic: OcrSemanticDao,
    private val hashes: PerceptualHashDao,
    private val decoder: BoundedMediaDecoder,
    private val embedding: VisualEmbeddingEngine,
    private val publisher: VisualVectorPublisher,
    private val generationId: String,
    private val componentHash: String,
    private val clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : IndexChannelExecutor {
    override suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome {
        check(claim.work.channel == IndexChannel.VISUAL)
        val leaseToken = checkNotNull(claim.work.leaseToken)
        val liveWork = indexState.workByLease(leaseToken) ?: return IndexExecutionOutcome.LeaseRejected
        if (
            liveWork != claim.work ||
            claim.work.pipelineVersion != Siglip2Contract.PipelineVersion ||
            claim.work.componentHash != componentHash
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

        val vector =
            try {
                withContext(inferenceDispatcher) {
                    decoder.decode(
                        MediaKey(asset.volumeName, asset.mediaStoreId),
                        Siglip2Contract.DecodeLongSide,
                    ).use { decoded -> embedding.encodeImage(decoded.bitmap) }
                }
            } catch (_: MediaDecodeAccessException) {
                return IndexExecutionOutcome.LeaseRejected
            } catch (_: MediaDecodeContentException) {
                return IndexExecutionOutcome.Permanent(ErrorCorruptMedia)
            } catch (_: MediaDecodeIoException) {
                return IndexExecutionOutcome.Retryable(ErrorMediaIo)
            } catch (_: VisualRuntimeException) {
                return IndexExecutionOutcome.Retryable(ErrorRuntime)
            }
        if (vector.size != Siglip2Contract.EmbeddingDimension) {
            return IndexExecutionOutcome.Retryable(ErrorRuntime)
        }

        val result =
            publisher.publish(
                VectorPublicationRequest(
                    publicationToken = claim.publicationToken,
                    generationId = generationId,
                    manifestRevision = "visual-manifest-${claim.publicationToken}",
                    snapshotId = "visual-snapshot-${claim.publicationToken}",
                    leaseTokens = listOf(leaseToken),
                    records =
                        listOf(
                            PublishedVectorRecord(
                                recordId = claim.work.assetId,
                                assetId = claim.work.assetId,
                                chunkOrdinal = 0,
                                sourceFingerprint = claim.work.sourceFingerprint,
                                accessRevision = claim.work.accessRevision,
                                vector = vector,
                            ),
                        ),
                    rankingConfigVersion = OcrSemanticChannelExecutor.RankingConfigVersion,
                    lexicalPublicationEpoch = semantic.maximumOcrPublicationEpoch(),
                    pHashPublicationEpoch =
                        hashes.maximumPublicationEpoch(
                            PerceptualHashV1.PipelineVersion,
                            PerceptualHashV1.ComponentHash,
                        ),
                    catalogWatermark = semantic.catalogRevision(),
                ),
            )
        return when (result) {
            VisualVectorPublicationResult.PUBLISHED -> IndexExecutionOutcome.Published
            VisualVectorPublicationResult.LEASE_REJECTED -> IndexExecutionOutcome.LeaseRejected
        }
    }

    companion object {
        const val ErrorCorruptMedia = "VISUAL_CORRUPT_MEDIA"
        const val ErrorMediaIo = "VISUAL_MEDIA_IO"
        const val ErrorRuntime = "VISUAL_RUNTIME"
    }
}
