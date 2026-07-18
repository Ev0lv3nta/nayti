package app.nayti.ui

import android.content.Context
import app.nayti.indexer.IndexExecutionGate
import app.nayti.storage.CatalogStorage
import app.nayti.storage.StorageContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SearchDataResetter @Inject constructor(
    @ApplicationContext context: Context,
    private val storage: CatalogStorage,
    private val executionGate: IndexExecutionGate,
) {
    private val appPrivateRoot = context.noBackupFilesDir.toPath().toAbsolutePath().normalize()
    private val vectorRoot = appPrivateRoot.resolve(StorageContract.VectorIndexDirectory).normalize()

    suspend fun reset() = executionGate.exclusive {
        withContext(Dispatchers.IO) {
            check(vectorRoot.startsWith(appPrivateRoot))
            storage.searchDataResetDao.reset()
            deleteVectorArtifacts()
        }
    }

    private fun deleteVectorArtifacts() {
        deleteBoundedTree(vectorRoot, MaximumEntries)
    }

    private companion object {
        const val MaximumEntries = 100_000
    }
}

internal fun deleteBoundedTree(root: Path, maximumEntries: Int) {
    require(maximumEntries > 0)
    if (!Files.exists(root)) return
    var visited = 0
    fun checkBound() {
        check(++visited <= maximumEntries) { "Vector index contains too many entries to reset safely" }
    }
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkBound()
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                failure?.let { throw it }
                checkBound()
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
