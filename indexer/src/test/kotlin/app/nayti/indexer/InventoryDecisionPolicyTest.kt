package app.nayti.indexer

import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaVolumeSnapshot
import app.nayti.storage.CatalogInventoryMode
import app.nayti.storage.CatalogVolumeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryDecisionPolicyTest {
    private val now = 100_000_000L
    private val current = MediaVolumeSnapshot("external_primary", "v1", 20)

    @Test
    fun firstSelectedDirtyAndVersionResetRequireFullInventory() {
        assertEquals(
            CatalogInventoryMode.FULL,
            InventoryDecisionPolicy.decide(null, current, MediaAccessScope.Full, false, now)?.mode,
        )
        assertEquals(
            CatalogInventoryMode.FULL,
            InventoryDecisionPolicy.decide(previous(), current, MediaAccessScope.Selected, false, now)?.mode,
        )
        assertEquals(
            CatalogInventoryMode.FULL,
            InventoryDecisionPolicy.decide(
                previous().copy(dirty = true),
                current,
                MediaAccessScope.Full,
                false,
                now,
            )?.mode,
        )
        assertEquals(
            CatalogInventoryMode.FULL,
            InventoryDecisionPolicy.decide(
                previous().copy(mediaStoreVersion = "old"),
                current,
                MediaAccessScope.Full,
                false,
                now,
            )?.mode,
        )
    }

    @Test
    fun stableGenerationSkipsAndNewGenerationUsesDelta() {
        assertNull(
            InventoryDecisionPolicy.decide(
                previous().copy(generationWatermark = 20),
                current,
                MediaAccessScope.Full,
                false,
                now,
            ),
        )
        val delta =
            InventoryDecisionPolicy.decide(
                previous().copy(generationWatermark = 12),
                current,
                MediaAccessScope.Full,
                false,
                now,
            )
        assertEquals(CatalogInventoryMode.INCREMENTAL, delta?.mode)
        assertEquals(12L, delta?.modifiedAfterGeneration)
    }

    @Test
    fun periodicFullScanBoundsMissedDeleteWindow() {
        val stale =
            previous().copy(
                lastFullInventoryAtMillis = now - InventoryDecisionPolicy.FullInventoryIntervalMillis,
            )
        assertEquals(
            CatalogInventoryMode.FULL,
            InventoryDecisionPolicy.decide(stale, current, MediaAccessScope.Full, false, now)?.mode,
        )
    }

    private fun previous() =
        CatalogVolumeEntity(
            volumeName = current.volumeName,
            mediaStoreVersion = current.version,
            generationWatermark = 10,
            isMounted = true,
            dirty = false,
            lastSuccessfulInventoryRunId = 1,
            lastFullInventoryAtMillis = now - 1_000,
        )
}
