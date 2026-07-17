package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexReadinessTest {
    @Test
    fun `pending excludes published and failed items`() {
        val readiness = IndexReadiness(
            discovered = 20,
            searchable = 12,
            failed = 3,
            isRunning = true,
        )

        assertEquals(5, readiness.pending)
    }

    @Test
    fun `rejects impossible progress`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexReadiness(discovered = 3, searchable = 3, failed = 1, isRunning = false)
        }
    }
}
