package app.nayti.indexer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexExecutionBudgetTest {
    @Test
    fun `stops exactly at planned cutoff`() {
        var nowMillis = 10_000L
        val budget = IndexExecutionBudget(IndexExecutionClock { nowMillis }, 5_000)

        assertTrue(budget.hasTimeRemaining())
        nowMillis = 14_999
        assertTrue(budget.hasTimeRemaining())
        nowMillis = 15_000
        assertFalse(budget.hasTimeRemaining())
    }

    @Test
    fun `monotonic origin does not need wall clock epoch`() {
        var nowMillis = -20L
        val budget = IndexExecutionBudget(IndexExecutionClock { nowMillis }, 100)

        nowMillis = 79
        assertTrue(budget.hasTimeRemaining())
        nowMillis = 80
        assertFalse(budget.hasTimeRemaining())
    }
}
