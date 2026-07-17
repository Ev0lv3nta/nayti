package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexResourcePolicyTest {
    @Test
    fun `manual execution may run unplugged under healthy constraints`() {
        val decision = IndexResourcePolicy.evaluate(healthy(charging = false), IndexExecutionInitiator.Manual)

        assertTrue(decision.canContinue)
    }

    @Test
    fun `automatic execution requires charging`() {
        val decision = IndexResourcePolicy.evaluate(healthy(charging = false), IndexExecutionInitiator.Automatic)

        assertEquals("CHARGING_REQUIRED", decision.constraintCode)
    }

    @Test
    fun `hard constraints have deterministic priority`() {
        val snapshot =
            healthy().copy(
                thermalStatus = 4,
                severeThermalStatus = 3,
                lowMemory = true,
                usableStorageBytes = 1,
                powerSaveMode = true,
                batteryPercent = 1,
            )

        assertEquals(
            "THERMAL_SEVERE",
            IndexResourcePolicy.evaluate(snapshot, IndexExecutionInitiator.Manual).constraintCode,
        )
        assertEquals(
            "MEMORY_PRESSURE",
            IndexResourcePolicy.evaluate(snapshot.copy(thermalStatus = 0), IndexExecutionInitiator.Manual)
                .constraintCode,
        )
        assertEquals(
            "STORAGE_RESERVE",
            IndexResourcePolicy.evaluate(
                snapshot.copy(thermalStatus = 0, lowMemory = false),
                IndexExecutionInitiator.Manual,
            ).constraintCode,
        )
    }

    @Test
    fun `battery safeguards apply to explicit user work`() {
        assertEquals(
            "BATTERY_SAVER",
            IndexResourcePolicy.evaluate(
                healthy().copy(powerSaveMode = true),
                IndexExecutionInitiator.Manual,
            ).constraintCode,
        )
        assertEquals(
            "BATTERY_LOW",
            IndexResourcePolicy.evaluate(
                healthy().copy(batteryPercent = 19),
                IndexExecutionInitiator.Manual,
            ).constraintCode,
        )
    }

    private fun healthy(charging: Boolean = true) =
        IndexResourceSnapshot(
            thermalStatus = 0,
            severeThermalStatus = 3,
            powerSaveMode = false,
            batteryPercent = 80,
            charging = charging,
            lowMemory = false,
            recentTrimPressure = false,
            usableStorageBytes = IndexResourcePolicy.MinimumStorageReserveBytes * 2,
        )
}
