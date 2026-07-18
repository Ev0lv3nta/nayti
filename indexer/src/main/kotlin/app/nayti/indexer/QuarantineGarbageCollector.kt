package app.nayti.indexer

import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.CatalogStorage
import app.nayti.storage.QuarantineDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QuarantineGcReport(
    val selectedAssets: Int,
    val purgedAssets: Int,
    val collectedSnapshots: Int,
    val deferred: Boolean,
)

/** Bounded privacy maintenance for derived data retained after selected-photo access changes. */
class QuarantineGarbageCollector(
    private val storage: CatalogStorage,
    vectorRoot: java.io.File,
    private val executionGate: IndexExecutionGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val pruner = QuarantineVectorPruner(vectorRoot, storage.vectorIndexDao, nowMillis)
    private val snapshots = VectorSnapshotGarbageCollector(vectorRoot, storage.vectorIndexDao)

    suspend fun runOnce(maxAssets: Int = QuarantineDao.MaximumBatchSize): QuarantineGcReport =
        withContext(Dispatchers.IO) {
            executionGate.exclusive {
                require(maxAssets in 1..QuarantineDao.MaximumBatchSize)
                val now = nowMillis()
                val cutoff = (now - RetentionMillis).coerceAtLeast(0)
                snapshots.replayPendingIntents()
                val expired = storage.quarantineDao.expiredAssets(cutoff, maxAssets)
                if (expired.isEmpty()) return@exclusive QuarantineGcReport(0, 0, 0, deferred = false)
                if (storage.vectorIndexDao.unfinishedActivationCandidateCount() != 0) {
                    return@exclusive QuarantineGcReport(expired.size, 0, 0, deferred = true)
                }
                val assetIds = expired.map { it.assetId }.toSet()
                val hasStoredVectors = storage.quarantineDao.vectorRecordCount(assetIds.toList()) != 0L
                val pointer = storage.vectorIndexDao.activePointer()
                val active = pointer?.snapshotId?.let { snapshotId -> storage.vectorIndexDao.snapshot(snapshotId) }
                val hasHistoricalRoots =
                    active?.parentSnapshotId != null ||
                        pointer?.rollbackSnapshotId != null ||
                        storage.vectorIndexDao.activationCandidates().any { candidate ->
                            candidate.state == ActivationCandidateState.ACTIVE
                        }
                pruner.prune(assetIds, forceIndependentRoot = hasStoredVectors && hasHistoricalRoots)

                val activeId = storage.vectorIndexDao.activeSnapshotId()
                var collected = 0
                var deferred = false
                storage.vectorIndexDao.snapshots()
                    .filterNot { it.snapshotId == activeId }
                    .forEach { snapshot ->
                        if (snapshots.collect(snapshot.snapshotId, nowMillis())) {
                            collected += 1
                        } else {
                            deferred = true
                        }
                    }
                if (deferred || storage.quarantineDao.vectorRecordCount(assetIds.toList()) != 0L) {
                    return@exclusive QuarantineGcReport(expired.size, 0, collected, deferred = true)
                }
                val purged = storage.quarantineDao.finalizePurge(assetIds.toList(), cutoff, nowMillis())
                QuarantineGcReport(expired.size, purged, collected, deferred = purged != expired.size)
            }
        }

    companion object {
        const val RetentionMillis = 30L * 24 * 60 * 60 * 1_000
    }
}
