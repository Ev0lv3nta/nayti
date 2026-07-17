package app.nayti.indexer

import android.system.Os
import android.system.OsConstants
import app.nayti.storage.ArtifactDeleteIntentEntity
import app.nayti.storage.ArtifactDeleteState
import app.nayti.storage.VectorIndexDao
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class VectorGcBoundary {
    AFTER_DELETE_INTENTS,
    AFTER_FIRST_UNLINK,
    BEFORE_DB_FINALIZE,
}

class VectorSnapshotGarbageCollector(
    rootDirectory: File,
    private val dao: VectorIndexDao,
    private val boundaryObserver: (VectorGcBoundary) -> Unit = {},
) {
    private val root = rootDirectory.canonicalFile

    suspend fun collect(snapshotId: String, nowMillis: Long): Boolean {
        if (dao.snapshot(snapshotId) == null) return true
        val activeId = dao.activeSnapshotId()
        if (snapshotId == activeId || snapshotId == activeId?.let { dao.snapshot(it)?.parentSnapshotId }) return false
        if (dao.liveQueryLeaseCount(snapshotId, nowMillis) != 0) return false
        val intents = dao.prepareSnapshotCollection(snapshotId, nowMillis)
        boundaryObserver(VectorGcBoundary.AFTER_DELETE_INTENTS)
        intents.forEachIndexed { index, intent ->
            deleteVerified(intent)
            if (intent.state == ArtifactDeleteState.PENDING) {
                check(dao.confirmDeleteIntent(intent.relativePath) == 1)
            }
            if (index == 0) boundaryObserver(VectorGcBoundary.AFTER_FIRST_UNLINK)
        }
        boundaryObserver(VectorGcBoundary.BEFORE_DB_FINALIZE)
        return dao.finalizeSnapshotCollection(snapshotId)
    }

    suspend fun replayPendingIntents(): Int {
        var replayed = 0
        dao.deleteIntentOwners().forEach { snapshotId ->
            dao.deleteIntents(snapshotId).forEach { intent ->
                if (intent.state == ArtifactDeleteState.PENDING) {
                    deleteVerified(intent)
                    check(dao.confirmDeleteIntent(intent.relativePath) == 1)
                    replayed += 1
                }
            }
            dao.finalizeSnapshotCollection(snapshotId)
        }
        return replayed
    }

    private fun deleteVerified(intent: ArtifactDeleteIntentEntity) {
        val file = resolveRelative(intent.relativePath)
        if (!file.exists()) return
        check(file.isFile)
        check(sha256Hex(file) == intent.expectedSha256)
        check(file.setWritable(true, true))
        check(file.delete())
        syncDirectory(file.parentFile!!)
    }

    private fun resolveRelative(path: String): File {
        val resolved = root.resolve(path).canonicalFile
        check(resolved.toPath().startsWith(root.toPath()))
        return resolved
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

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
}
