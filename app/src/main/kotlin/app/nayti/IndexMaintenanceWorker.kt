package app.nayti

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nayti.indexer.QuarantineGarbageCollector
import app.nayti.storage.CatalogStorage
import app.nayti.storage.QuarantineDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@HiltWorker
class IndexMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val storage: CatalogStorage,
    private val quarantineGarbageCollector: QuarantineGarbageCollector,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val completed =
                withTimeoutOrNull(ExecutionBudget.toMillis()) {
                    storage.indexStateDao.recoverExpiredExecution(System.currentTimeMillis())
                    repeat(MaximumQuarantineBatches) {
                        val report = quarantineGarbageCollector.runOnce()
                        if (report.deferred) return@withTimeoutOrNull false
                        if (report.selectedAssets < QuarantineDao.MaximumBatchSize) {
                            return@withTimeoutOrNull true
                        }
                    }
                    false
                } ?: false
            when {
                completed -> Result.success()
                runAttemptCount < MaximumAttempts -> Result.retry()
                else -> Result.failure()
            }
        }

    companion object {
        val ExecutionBudget: Duration = Duration.ofMinutes(4)
        const val MaximumAttempts = 3
        const val MaximumQuarantineBatches = 4
    }
}
