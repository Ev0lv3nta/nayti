package app.nayti

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.nayti.indexing.AppForegroundIndexingHost
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NaytiApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var maintenanceScheduler: IndexMaintenanceScheduler
    @Inject lateinit var appForegroundIndexingHost: AppForegroundIndexingHost

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundIndexingHost)
        maintenanceScheduler.ensureScheduled()
    }
}
