package app.nayti

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IndexMaintenanceScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun ensureScheduled() {
        val constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
        workManager.enqueueUniqueWork(
            BootstrapWorkName,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexMaintenanceWorker>()
                .setConstraints(constraints)
                .setInitialDelay(BootstrapDelay)
                .addTag(MaintenanceTag)
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IndexMaintenanceWorker>(RepeatInterval, FlexInterval)
                .setConstraints(constraints)
                .addTag(MaintenanceTag)
                .build(),
        )
    }

    companion object {
        const val BootstrapWorkName = "index-maintenance-bootstrap-v1"
        const val PeriodicWorkName = "index-maintenance-periodic-v1"
        const val MaintenanceTag = "index-maintenance"
        val BootstrapDelay: Duration = Duration.ofSeconds(30)
        val RepeatInterval: Duration = Duration.ofHours(12)
        val FlexInterval: Duration = Duration.ofHours(2)
    }
}
