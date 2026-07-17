package app.nayti

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexMaintenanceSchedulerInstrumentedTest {
    @Test
    fun uniqueMaintenanceCanBeRescheduledAfterCancellation() {
        val context = ApplicationProvider.getApplicationContext<NaytiApplication>()
        val scheduler = IndexMaintenanceScheduler(context)
        val workManager = WorkManager.getInstance(context)

        try {
            scheduler.ensureScheduled()
            scheduler.ensureScheduled()

            assertEquals(1, unfinished(workManager, IndexMaintenanceScheduler.BootstrapWorkName))
            assertEquals(1, unfinished(workManager, IndexMaintenanceScheduler.PeriodicWorkName))

            workManager.cancelUniqueWork(IndexMaintenanceScheduler.BootstrapWorkName)
                .result.get(10, TimeUnit.SECONDS)
            assertTrue(
                workManager.getWorkInfosForUniqueWork(IndexMaintenanceScheduler.BootstrapWorkName)
                    .get(10, TimeUnit.SECONDS)
                    .all { info -> info.state.isFinished },
            )

            scheduler.ensureScheduled()
            assertEquals(1, unfinished(workManager, IndexMaintenanceScheduler.BootstrapWorkName))
            assertEquals(1, unfinished(workManager, IndexMaintenanceScheduler.PeriodicWorkName))
        } finally {
            scheduler.ensureScheduled()
        }
    }

    private fun unfinished(workManager: WorkManager, name: String): Int =
        workManager.getWorkInfosForUniqueWork(name)
            .get(10, TimeUnit.SECONDS)
            .count { info -> info.state !in FinishedStates }

    private companion object {
        val FinishedStates = setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)
    }
}
