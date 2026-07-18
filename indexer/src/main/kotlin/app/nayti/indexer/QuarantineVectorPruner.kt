package app.nayti.indexer

import app.nayti.search.engine.VectorManifestSegment
import app.nayti.search.engine.VectorManifestV1
import app.nayti.search.engine.VectorSegmentRecord
import app.nayti.search.engine.VectorSegmentV1Reader
import app.nayti.search.engine.VectorSegmentV1Writer
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.QuarantineVectorChannelCommit
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorManifestEntity
import app.nayti.storage.VectorManifestSegmentEntity
import app.nayti.storage.VectorSegmentArtifactEntity
import app.nayti.storage.VectorSegmentRecordEntity
import java.io.File
import java.util.UUID

/** Re-roots the active snapshot without quarantined records so old immutable files can be unlinked. */
class QuarantineVectorPruner(
    rootDirectory: File,
    private val vectors: VectorIndexDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ids: () -> UUID = UUID::randomUUID,
) {
    private val files = ImmutableVectorFiles(rootDirectory)

    suspend fun prune(
        assetIds: Set<Long>,
        forceIndependentRoot: Boolean = false,
    ): ActivationSnapshotEntity? {
        require(assetIds.isNotEmpty() && assetIds.size <= 256 && assetIds.all { it > 0 })
        check(vectors.unfinishedActivationCandidateCount() == 0)
        val activeId = vectors.activeSnapshotId() ?: return null
        val active = checkNotNull(vectors.snapshot(activeId))
        val sourceChannels = vectors.snapshotChannels(activeId).associateBy { it.channel }
        val vectorChannels = mutableListOf<QuarantineVectorChannelCommit>()
        val sourceRevisions =
            listOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL).mapNotNull { channel ->
                val revision =
                if (channel == IndexChannel.OCR_SEMANTIC) {
                    active.semanticManifestRevision
                } else {
                    active.visualManifestRevision
                } ?: return@mapNotNull null
                channel to revision
            }
        val hasVectorRecords =
            sourceRevisions.any { (_, revision) ->
                vectors.manifestSegments(revision).any { entry ->
                    vectors.segmentRecords(entry.segmentSha256).any { it.assetId in assetIds }
                }
        }
        if (!hasVectorRecords && !forceIndependentRoot) return null
        sourceRevisions.forEach { (_, revision) ->
            prepareChannel(revision, assetIds)?.let(vectorChannels::add)
        }

        val token = ids().toString().replace("-", "")
        val snapshotId = "quarantine-snapshot-$token"
        val semanticRevision =
            vectorChannels.singleOrNull { it.manifest.channel == IndexChannel.OCR_SEMANTIC }?.manifest?.revision
        val visualRevision =
            vectorChannels.singleOrNull { it.manifest.channel == IndexChannel.VISUAL }?.manifest?.revision
        val snapshot =
            active.copy(
                snapshotId = snapshotId,
                parentSnapshotId = null,
                semanticManifestRevision = semanticRevision,
                visualManifestRevision = visualRevision,
                createdAtMillis = nowMillis(),
            )
        val commitsByChannel = vectorChannels.associateBy { it.manifest.channel }
        val channels =
            buildList {
                listOf(IndexChannel.OCR, IndexChannel.PHASH).forEach { channel ->
                    sourceChannels[channel]?.let { source ->
                        add(source.copy(snapshotId = snapshotId, inheritedFromSnapshotId = null))
                    }
                }
                commitsByChannel.forEach { (channel, commit) ->
                    val generation = checkNotNull(vectors.generation(commit.generationId))
                    add(
                        ActivationSnapshotChannelEntity(
                            snapshotId = snapshotId,
                            channel = channel,
                            pipelineVersion = generation.pipelineVersion,
                            componentHash = generation.componentHash,
                            embeddingSpaceHash = generation.embeddingSpaceHash,
                            generationId = generation.generationId,
                            manifestRevision = commit.manifest.revision,
                            inheritedFromSnapshotId = null,
                        ),
                    )
                }
            }.sortedBy { it.channel }
        return vectors.commitQuarantinePrune(
            expectedActiveSnapshotId = activeId,
            sanitizedSnapshot = snapshot,
            channels = channels,
            vectorChannels = vectorChannels,
            nowMillis = nowMillis(),
        )
    }

    private suspend fun prepareChannel(
        revision: String,
        removedAssetIds: Set<Long>,
    ): QuarantineVectorChannelCommit? {
        val parent = checkNotNull(vectors.manifest(revision))
        val generation = checkNotNull(vectors.generation(parent.generationId))
        val artifacts = vectors.manifestSegments(revision).map { entry -> checkNotNull(vectors.segment(entry.segmentSha256)) }
        val resulting = mutableListOf<VectorSegmentArtifactEntity>()
        val created = mutableListOf<VectorSegmentArtifactEntity>()
        val createdRecords = mutableListOf<VectorSegmentRecordEntity>()
        artifacts.forEach { artifact ->
            val metadata = vectors.segmentRecords(artifact.sha256)
            if (metadata.none { it.assetId in removedAssetIds }) {
                resulting += artifact
                return@forEach
            }
            val bytes = files.readVerified(artifact.relativePath, artifact.byteLength, artifact.sha256)
            val decoded = VectorSegmentV1Reader.decode(bytes)
            check(decoded.records.size == metadata.size)
            val kept =
                decoded.records.mapIndexedNotNull { ordinal, record ->
                    val stored = metadata[ordinal]
                    check(
                        stored.ordinal == ordinal &&
                            stored.recordId == record.recordId &&
                            stored.assetId == record.assetId &&
                            stored.chunkOrdinal == record.ordinal,
                    )
                    if (stored.assetId in removedAssetIds) null else StoredVector(record, stored)
                }
            if (kept.isEmpty()) return@forEach
            val encoded =
                VectorSegmentV1Writer.encode(
                    channel = generation.channel.toSegmentChannel(),
                    embeddingSpaceHash = generation.embeddingSpaceHash,
                    records = kept.map { it.record },
                    segmentId = ids(),
                )
            val sealed = files.sealSegment("quarantine-${encoded.segmentId}", encoded.bytes, encoded.sha256)
            val rewritten =
                artifact.copy(
                    sha256 = encoded.sha256,
                    segmentId = encoded.segmentId.toString(),
                    relativePath = sealed.relativePath,
                    byteLength = encoded.bytes.size.toLong(),
                    recordCount = kept.size,
                    createdAtMillis = nowMillis(),
                    compactionLevel = Math.incrementExact(artifact.compactionLevel),
                )
            resulting += rewritten
            created += rewritten
            createdRecords +=
                kept.mapIndexed { ordinal, item ->
                    item.metadata.copy(segmentSha256 = rewritten.sha256, ordinal = ordinal)
                }
        }
        if (resulting.isEmpty()) return null

        val token = ids().toString().replace("-", "")
        val manifestRevision = "quarantine-manifest-$token"
        val encodedManifest =
            VectorManifestV1.encode(
                revision = manifestRevision,
                generationId = generation.generationId,
                parentRevision = null,
                channel = generation.channel.toSegmentChannel(),
                embeddingSpaceHash = generation.embeddingSpaceHash,
                dimension = generation.dimension,
                segments =
                    resulting.map { artifact ->
                        VectorManifestSegment(
                            relativePath = artifact.relativePath,
                            byteLength = artifact.byteLength,
                            sha256 = artifact.sha256,
                            recordCount = artifact.recordCount,
                        )
                    },
            )
        val sealedManifest =
            files.sealManifest("quarantine-$token", encodedManifest.bytes, encodedManifest.sha256)
        val manifest =
            VectorManifestEntity(
                revision = manifestRevision,
                generationId = generation.generationId,
                parentRevision = null,
                channel = generation.channel,
                relativePath = sealedManifest.relativePath,
                byteLength = encodedManifest.bytes.size.toLong(),
                sha256 = encodedManifest.sha256,
                segmentCount = resulting.size,
                recordCount = encodedManifest.recordCount,
                createdAtMillis = nowMillis(),
            )
        return QuarantineVectorChannelCommit(
            generationId = generation.generationId,
            segments = created,
            records = createdRecords,
            manifest = manifest,
            manifestEntries =
                resulting.mapIndexed { ordinal, artifact ->
                    VectorManifestSegmentEntity(manifestRevision, ordinal, artifact.sha256)
                },
        )
    }

    private data class StoredVector(
        val record: VectorSegmentRecord,
        val metadata: VectorSegmentRecordEntity,
    )

    private fun String.toSegmentChannel() =
        when (this) {
            IndexChannel.VISUAL -> app.nayti.search.engine.VectorSegmentChannel.VISUAL
            IndexChannel.OCR_SEMANTIC -> app.nayti.search.engine.VectorSegmentChannel.OCR_SEMANTIC
            else -> error("Unsupported vector channel $this")
        }
}
