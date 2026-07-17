package app.nayti.indexer

data class IndexResourceSnapshot(
    val thermalStatus: Int,
    val severeThermalStatus: Int,
    val powerSaveMode: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val lowMemory: Boolean,
    val recentTrimPressure: Boolean,
    val usableStorageBytes: Long,
)

enum class IndexExecutionInitiator {
    Manual,
    Automatic,
}

data class IndexResourceDecision(
    val canContinue: Boolean,
    val constraintCode: String? = null,
)

fun interface IndexResourceGovernor {
    fun evaluate(initiator: IndexExecutionInitiator): IndexResourceDecision
}

object IndexResourcePolicy {
    const val MinimumBatteryPercent = 20
    const val MinimumStorageReserveBytes = 512L * 1024 * 1024

    fun evaluate(
        snapshot: IndexResourceSnapshot,
        initiator: IndexExecutionInitiator,
    ): IndexResourceDecision =
        when {
            snapshot.thermalStatus >= snapshot.severeThermalStatus -> blocked("THERMAL_SEVERE")
            snapshot.lowMemory || snapshot.recentTrimPressure -> blocked("MEMORY_PRESSURE")
            snapshot.usableStorageBytes < MinimumStorageReserveBytes -> blocked("STORAGE_RESERVE")
            snapshot.powerSaveMode -> blocked("BATTERY_SAVER")
            snapshot.batteryPercent != null && snapshot.batteryPercent < MinimumBatteryPercent ->
                blocked("BATTERY_LOW")
            initiator == IndexExecutionInitiator.Automatic && !snapshot.charging ->
                blocked("CHARGING_REQUIRED")
            else -> IndexResourceDecision(canContinue = true)
        }

    private fun blocked(code: String) =
        IndexResourceDecision(canContinue = false, constraintCode = code)
}
