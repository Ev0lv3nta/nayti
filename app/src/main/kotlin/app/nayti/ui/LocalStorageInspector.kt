package app.nayti.ui

import android.content.Context
import app.nayti.storage.StorageContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalStorageSummary(
    val indexBytes: Long,
    val modelBytes: Long,
)

@Singleton
class LocalStorageInspector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun measure(modelBytes: Long): LocalStorageSummary = withContext(Dispatchers.IO) {
        val database = context.getDatabasePath(StorageContract.DatabaseFileName)
        val databaseBytes = listOf(
            database,
            File("${database.path}-wal"),
            File("${database.path}-shm"),
        ).sumOf { file -> file.safeLength() }
        val vectors = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory)
        LocalStorageSummary(
            indexBytes = Math.addExact(databaseBytes, vectors.boundedTreeBytes()),
            modelBytes = modelBytes.coerceAtLeast(0L),
        )
    }

    private fun File.boundedTreeBytes(): Long {
        if (!exists()) return 0L
        var bytes = 0L
        walkTopDown().take(MaximumVisitedEntries).forEach { entry ->
            if (entry.isFile) bytes = Math.addExact(bytes, entry.safeLength())
        }
        return bytes
    }

    private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L

    private companion object {
        const val MaximumVisitedEntries = 100_000
    }
}
