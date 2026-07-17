package app.nayti.indexer

import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.MediaDecodeAccessException
import app.nayti.platform.media.MediaDecodeContentException
import app.nayti.platform.media.MediaDecodeIoException
import app.nayti.platform.media.MediaKey
import app.nayti.search.engine.similarity.ArgbImage
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.PerceptualHashCodec
import app.nayti.storage.PerceptualHashDao
import app.nayti.storage.PerceptualHashDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface PerceptualHashExecutorClock {
    fun nowMillis(): Long
}

/** Publishes the canonical pHash of one current MediaStore asset without retaining bitmap memory. */
class PerceptualHashChannelExecutor(
    private val hashes: PerceptualHashDao,
    private val decoder: BoundedMediaDecoder,
    private val clock: PerceptualHashExecutorClock = PerceptualHashExecutorClock(System::currentTimeMillis),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : IndexChannelExecutor {
    override suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome {
        val asset = hashes.catalogAsset(claim.work.assetId) ?: return IndexExecutionOutcome.LeaseRejected
        val access = hashes.accessObservation() ?: return IndexExecutionOutcome.LeaseRejected
        if (
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != claim.work.sourceFingerprint ||
            access.processAccessRevision != claim.work.accessRevision ||
            access.accessScope == "None" ||
            claim.work.pipelineVersion != PerceptualHashV1.PipelineVersion ||
            claim.work.componentHash != PerceptualHashV1.ComponentHash
        ) {
            return IndexExecutionOutcome.LeaseRejected
        }

        val hashBits =
            try {
                withContext(computeDispatcher) {
                    decoder.decode(
                        MediaKey(asset.volumeName, asset.mediaStoreId),
                        PerceptualHashV1.DecodeLongSide,
                    ).use { decoded ->
                        val pixels = IntArray(Math.multiplyExact(decoded.decodedWidth, decoded.decodedHeight))
                        decoded.bitmap.getPixels(
                            pixels,
                            0,
                            decoded.decodedWidth,
                            0,
                            0,
                            decoded.decodedWidth,
                            decoded.decodedHeight,
                        )
                        PerceptualHashV1.compute(
                            ArgbImage(decoded.decodedWidth, decoded.decodedHeight, pixels),
                        )
                    }
                }
            } catch (_: MediaDecodeAccessException) {
                return IndexExecutionOutcome.LeaseRejected
            } catch (_: MediaDecodeContentException) {
                return IndexExecutionOutcome.Permanent(ErrorCorruptMedia)
            } catch (_: MediaDecodeIoException) {
                return IndexExecutionOutcome.Retryable(ErrorMediaIo)
            }

        val draft =
            PerceptualHashDraft(
                assetId = claim.work.assetId,
                sourceFingerprint = claim.work.sourceFingerprint,
                accessRevision = claim.work.accessRevision,
                pipelineVersion = claim.work.pipelineVersion,
                componentHash = claim.work.componentHash,
                hashBits = hashBits,
            )
        val publication =
            hashes.commit(
                leaseToken = checkNotNull(claim.work.leaseToken),
                publicationToken = claim.publicationToken,
                draft = draft,
                expectedIdentity = PerceptualHashCodec.identity(draft),
                nowMillis = clock.nowMillis(),
            )
        return if (publication == null) IndexExecutionOutcome.LeaseRejected else IndexExecutionOutcome.Published
    }

    companion object {
        const val ErrorCorruptMedia = "PHASH_CORRUPT_MEDIA"
        const val ErrorMediaIo = "PHASH_MEDIA_IO"
    }
}
