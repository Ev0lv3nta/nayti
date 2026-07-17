package app.nayti.indexer

import app.nayti.storage.IndexChannel
import app.nayti.storage.VectorIndexDao
import java.io.File
import java.util.UUID

class VectorIndexCompactor(
    rootDirectory: File,
    private val dao: VectorIndexDao,
    private val ids: () -> UUID = UUID::randomUUID,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val store = VectorCompactionStore(rootDirectory, dao, nowMillis)

    suspend fun compactAvailable(
        maxOperations: Int,
        channel: String = IndexChannel.OCR_SEMANTIC,
    ): Int {
        require(maxOperations > 0)
        require(channel == IndexChannel.OCR_SEMANTIC || channel == IndexChannel.VISUAL)
        var completed = 0
        while (completed < maxOperations && compactOne(channel)) completed += 1
        return completed
    }

    private suspend fun compactOne(channel: String): Boolean {
        val active = dao.activeSnapshotId()?.let { dao.snapshot(it) } ?: return false
        val revision =
            if (channel == IndexChannel.VISUAL) {
                active.visualManifestRevision
            } else {
                active.semanticManifestRevision
            } ?: return false
        val manifest = checkNotNull(dao.manifest(revision))
        check(manifest.channel == channel)
        val entries = dao.manifestSegments(revision)
        val artifacts = entries.map { entry -> checkNotNull(dao.segment(entry.segmentSha256)) }
        val plan =
            VectorCompactionPlanner.plan(
                artifacts.map { artifact ->
                    CompactionSegment(
                        recordCount = artifact.recordCount,
                        level = artifact.compactionLevel,
                    )
                },
            ) ?: return false
        val token = ids().toString().replace("-", "")
        store.compact(
            VectorCompactionRequest(
                compactionToken = "compact-$token",
                generationId = manifest.generationId,
                firstSegmentOrdinal = plan.firstSegmentOrdinal,
                segmentCount = plan.segmentCount,
                manifestRevision = "compact-manifest-$token",
                snapshotId = "compact-snapshot-$token",
                rankingConfigVersion = active.rankingConfigVersion,
                lexicalPublicationEpoch = active.lexicalPublicationEpoch,
                pHashPublicationEpoch = active.pHashPublicationEpoch,
                catalogWatermark = active.catalogWatermark,
                segmentId = ids(),
            ),
        )
        return true
    }
}
