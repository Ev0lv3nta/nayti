package app.nayti.indexer

import app.nayti.search.engine.NativeVectorIndex
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorManifestEntity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

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
        val activeAfter = recoverActive(activeBefore, deepVerifySegments)
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

    private suspend fun recoverActive(activeBefore: String?, deepVerifySegments: Boolean): String? {
        var candidateId = activeBefore
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
                candidateId = candidate.parentSnapshotId
                continue
            }
            if (validateSnapshot(candidate, deepVerifySegments)) break
            candidateId = candidate.parentSnapshotId
        }
        if (candidateId != activeBefore) {
            if (!dao.replaceActiveAfterRecovery(activeBefore, candidateId)) return dao.activeSnapshotId()
        }
        return candidateId
    }

    private suspend fun validateSnapshot(snapshot: ActivationSnapshotEntity, deepVerifySegments: Boolean): Boolean {
        val revisions = listOfNotNull(snapshot.semanticManifestRevision, snapshot.visualManifestRevision)
        if (revisions.isEmpty()) return false
        return revisions.all { revision ->
            val manifest = dao.manifest(revision) ?: return@all false
            validateManifest(manifest, deepVerifySegments)
        }
    }

    private suspend fun validateManifest(manifest: VectorManifestEntity, deepVerifySegments: Boolean): Boolean {
        val manifestFile = resolveRelative(manifest.relativePath) ?: return false
        if (!validateFile(manifestFile, manifest.byteLength, manifest.sha256, verifyHash = true)) return false
        val entries = dao.manifestSegments(manifest.revision)
        if (entries.size != manifest.segmentCount) return false
        return entries.all { entry ->
            val artifact = dao.segment(entry.segmentSha256) ?: return@all false
            val file = resolveRelative(artifact.relativePath) ?: return@all false
            if (!validateFile(file, artifact.byteLength, artifact.sha256, verifyHash = deepVerifySegments)) {
                return@all false
            }
            if (!deepVerifySegments) return@all true
            runCatching {
                NativeVectorIndex.mappedRecordCount(
                    path = file.absolutePath,
                    expectedLength = artifact.byteLength,
                    expectedSha256 = decodeSha256(artifact.sha256),
                ) == artifact.recordCount
            }.getOrDefault(false)
        }
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

    private fun validateFile(file: File, length: Long, sha256: String, verifyHash: Boolean): Boolean =
        file.isFile && file.length() == length && (!verifyHash || sha256Hex(file) == sha256)

    private fun resolveRelative(path: String): File? {
        val resolved = runCatching { root.resolve(path).canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun decodeSha256(value: String): ByteArray =
        ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
