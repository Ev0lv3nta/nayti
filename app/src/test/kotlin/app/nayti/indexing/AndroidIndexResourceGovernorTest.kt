package app.nayti.indexing

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class AndroidIndexResourceGovernorTest {
    @Test
    fun `active low-memory callbacks pause indexing`() {
        assertTrue(isActiveMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(isActiveMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun `leaving the app does not masquerade as memory pressure`() {
        assertFalse(isActiveMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(isActiveMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertFalse(isActiveMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
