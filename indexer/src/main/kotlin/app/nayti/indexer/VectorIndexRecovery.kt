package app.nayti.indexer

import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.VectorIndexDao
import java.io.File

data class VectorRecoveryReport(
    val activeBefore: String?,
    val activeAfter: String?,
    val abandonedPublications: Int,
    val expiredQueryLeases: Int,
    val replayedDeleteIntents: Int,
    val deletedTemps: Int,
    val deletedOrphans: Int,
)

class VectorIndexRecovery(
    rootDirectory: File,
    private val dao: VectorIndexDao,
) {
    private val root = rootDirectory.canonicalFile
    private val verifier = VectorSnapshotIntegrityVerifier(root, dao)

    suspend fun recover(
        nowMillis: Long,
        orphanGraceMillis: Long,
        deepVerifySegments: Boolean,
    ): VectorRecoveryReport {
        require(nowMillis >= 0 && orphanGraceMillis >= 0)
        val activeBefore = dao.activeSnapshotId()
        val replayedDeleteIntents = VectorSnapshotGarbageCollector(root, dao).replayPendingIntents()
        var abandoned = 0
        dao.stagedPublications().forEach { publication ->
            abandoned += dao.abandonStagedPublication(publication.publicationToken, nowMillis)
        }
        val expiredLeases = dao.expireQueryLeases(nowMillis)
        val deletedTemps = deleteTemps()
        val activeAfter = recoverActive(activeBefore, nowMillis, deepVerifySegments)
        val deletedOrphans = deleteOrphans(nowMillis, orphanGraceMillis)
        return VectorRecoveryReport(
            activeBefore = activeBefore,
            activeAfter = activeAfter,
            abandonedPublications = abandoned,
            expiredQueryLeases = expiredLeases,
            replayedDeleteIntents = replayedDeleteIntents,
            deletedTemps = deletedTemps,
            deletedOrphans = deletedOrphans,
        )
    }

    private suspend fun recoverActive(
        activeBefore: String?,
        nowMillis: Long,
        deepVerifySegments: Boolean,
    ): String? {
        var candidateId = activeBefore
        val explicitRollback = dao.activePointer()?.rollbackSnapshotId
        val visited = mutableSetOf<String>()
        while (candidateId != null) {
            if (!visited.add(candidateId)) {
                candidateId = null
                break
            }
            val candidate = dao.snapshot(candidateId)
            if (candidate == null) {
                candidateId = null
                break
            }
            if (dao.deleteIntentCount(candidateId) != 0) {
                candidateId = nextRecoveryCandidate(candidateId, activeBefore, explicitRollback, candidate)
                continue
            }
            if (verifier.verify(candidate, deepVerifySegments)) break
            candidateId = nextRecoveryCandidate(candidateId, activeBefore, explicitRollback, candidate)
        }
        if (candidateId != activeBefore) {
            if (!dao.replaceActiveAfterRecovery(activeBefore, candidateId, nowMillis)) return dao.activeSnapshotId()
        }
        return candidateId
    }

    private fun nextRecoveryCandidate(
        currentId: String,
        activeBefore: String?,
        explicitRollback: String?,
        current: ActivationSnapshotEntity,
    ): String? =
        if (currentId == activeBefore && explicitRollback != null && explicitRollback != currentId) {
            explicitRollback
        } else {
            current.parentSnapshotId
        }

    private fun deleteTemps(): Int {
        val staging = root.resolve("staging")
        if (!staging.isDirectory) return 0
        var count = 0
        staging.listFiles().orEmpty().filter { it.isFile && it.extension == "tmp" }.forEach { file ->
            if (file.delete()) count += 1
        }
        return count
    }

    private suspend fun deleteOrphans(nowMillis: Long, graceMillis: Long): Int {
        val referenced = buildSet {
            dao.segments().forEach { add(it.relativePath) }
            dao.manifests().forEach { add(it.relativePath) }
        }
        val threshold = if (nowMillis >= graceMillis) nowMillis - graceMillis else 0
        var count = 0
        listOf(root.resolve("segments"), root.resolve("manifests")).forEach { directory ->
            directory.listFiles().orEmpty()
                .filter { file ->
                    file.isFile &&
                        relativePath(file) !in referenced &&
                        file.lastModified() <= threshold
                }
                .forEach { file ->
                    check(file.setWritable(true, true))
                    if (file.delete()) count += 1
                }
        }
        return count
    }

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}
