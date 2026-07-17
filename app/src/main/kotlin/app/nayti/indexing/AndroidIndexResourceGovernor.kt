package app.nayti.indexing

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import app.nayti.indexer.IndexExecutionInitiator
import app.nayti.indexer.IndexResourceDecision
import app.nayti.indexer.IndexResourceGovernor
import app.nayti.indexer.IndexResourcePolicy
import app.nayti.indexer.IndexResourceSnapshot
import java.util.concurrent.atomic.AtomicLong

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class AndroidIndexResourceGovernor(
    context: Context,
) : IndexResourceGovernor, ComponentCallbacks2 {
    private val applicationContext = context.applicationContext
    private val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
    private val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val storageManager = applicationContext.getSystemService(StorageManager::class.java)
    private val memoryPressureUntilMillis = AtomicLong(0)

    init {
        applicationContext.registerComponentCallbacks(this)
    }

    override fun evaluate(initiator: IndexExecutionInitiator): IndexResourceDecision {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val batteryPercent =
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 }
        return IndexResourcePolicy.evaluate(
            snapshot =
                IndexResourceSnapshot(
                    thermalStatus = powerManager.currentThermalStatus,
                    severeThermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                    powerSaveMode = powerManager.isPowerSaveMode,
                    batteryPercent = batteryPercent,
                    charging = batteryManager.isCharging,
                    lowMemory = memory.lowMemory,
                    recentTrimPressure =
                        SystemClock.elapsedRealtime() < memoryPressureUntilMillis.get(),
                    usableStorageBytes = availableStorageBytes(),
                ),
            initiator = initiator,
        )
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            rememberMemoryPressure()
        }
    }

    override fun onLowMemory() {
        rememberMemoryPressure()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun rememberMemoryPressure() {
        memoryPressureUntilMillis.set(SystemClock.elapsedRealtime() + MemoryPressureCooldownMillis)
    }

    private fun availableStorageBytes(): Long {
        val root = applicationContext.noBackupFilesDir
        val allocatable =
            runCatching {
                storageManager.getAllocatableBytes(storageManager.getUuidForPath(root))
            }.getOrNull()
        return minOf(root.usableSpace, allocatable ?: Long.MAX_VALUE)
    }

    private companion object {
        const val MemoryPressureCooldownMillis = 60_000L
    }
}
