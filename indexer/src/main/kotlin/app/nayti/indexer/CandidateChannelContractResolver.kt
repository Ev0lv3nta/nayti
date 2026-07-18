package app.nayti.indexer

import app.nayti.ml.runtime.semantic.User2EmbeddingSpaceIdentity
import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.ml.runtime.visual.Siglip2EmbeddingSpaceIdentity
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.VectorIndexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves update contracts from verified pack artifacts and preserves only exact parent components. */
class CandidateChannelContractResolver(
    private val vectors: VectorIndexDao,
    private val packs: InstalledOcrPackResolver,
) {
    suspend fun resolve(
        pack: ModelPackEntity,
        candidateSnapshotId: String,
    ): List<ActivationSnapshotChannelEntity> {
        val parentId = checkNotNull(vectors.activeSnapshotId())
        val parent = vectors.snapshotChannels(parentId)
        check(parent.isNotEmpty())
        val installed = packs.resolve(pack.packId, pack.packVersion)
        val (semanticSpace, visualSpace) = withContext(Dispatchers.IO) {
            User2EmbeddingSpaceIdentity.calculate(installed.payloadDirectory.parent) to
                Siglip2EmbeddingSpaceIdentity.calculate(installed.payloadDirectory.parent)
        }
        return parent.map { active ->
            val desired =
                when (active.channel) {
                    IndexChannel.OCR -> active.copy(
                        snapshotId = candidateSnapshotId,
                        pipelineVersion = OcrIndexingRuntime.PipelineVersion,
                        componentHash = pack.manifestSha256,
                        generationId = null,
                        manifestRevision = null,
                        inheritedFromSnapshotId = null,
                    )
                    IndexChannel.PHASH -> active.copy(
                        snapshotId = candidateSnapshotId,
                        pipelineVersion = PerceptualHashV1.PipelineVersion,
                        componentHash = PerceptualHashV1.ComponentHash,
                        generationId = null,
                        manifestRevision = null,
                        inheritedFromSnapshotId = null,
                    )
                    IndexChannel.OCR_SEMANTIC -> active.copy(
                        snapshotId = candidateSnapshotId,
                        pipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                        componentHash = pack.manifestSha256,
                        embeddingSpaceHash = semanticSpace,
                        generationId = null,
                        manifestRevision = null,
                        inheritedFromSnapshotId = null,
                    )
                    IndexChannel.VISUAL -> active.copy(
                        snapshotId = candidateSnapshotId,
                        pipelineVersion = Siglip2Contract.PipelineVersion,
                        componentHash = pack.manifestSha256,
                        embeddingSpaceHash = visualSpace,
                        generationId = null,
                        manifestRevision = null,
                        inheritedFromSnapshotId = null,
                    )
                    else -> error("Unsupported index channel ${active.channel}")
                }
            if (active.sameContract(desired)) {
                active.copy(snapshotId = candidateSnapshotId, inheritedFromSnapshotId = parentId)
            } else {
                desired
            }
        }.sortedBy(ActivationSnapshotChannelEntity::channel)
    }

    private fun ActivationSnapshotChannelEntity.sameContract(other: ActivationSnapshotChannelEntity): Boolean =
        pipelineVersion == other.pipelineVersion &&
            componentHash == other.componentHash &&
            embeddingSpaceHash == other.embeddingSpaceHash
}
