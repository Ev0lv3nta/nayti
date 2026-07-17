package app.nayti.indexer

import app.nayti.storage.IndexStateDao
import app.nayti.storage.VectorIndexDao
import java.io.File

data class IndexStartupRecoveryReport(
    val expiredExecutionWindows: Int,
    val releasedWorkLeases: Int,
    val vector: VectorRecoveryReport,
)

class IndexStartupRecovery(
    vectorRootDirectory: File,
    private val indexState: IndexStateDao,
    vectorIndex: VectorIndexDao,
) {
    private val vectorRecovery = VectorIndexRecovery(vectorRootDirectory, vectorIndex)

    suspend fun recover(
        nowMillis: Long,
        orphanGraceMillis: Long,
        deepVerifySegments: Boolean,
    ): IndexStartupRecoveryReport {
        val (expiredWindows, releasedWork) = indexState.recoverExpiredExecution(nowMillis)
        val vector = vectorRecovery.recover(nowMillis, orphanGraceMillis, deepVerifySegments)
        return IndexStartupRecoveryReport(expiredWindows, releasedWork, vector)
    }
}
