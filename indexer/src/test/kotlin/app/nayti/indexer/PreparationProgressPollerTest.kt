package app.nayti.indexer

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparationProgressPollerTest {
    @Test
    fun ticksAtTheConfiguredCadenceAndStopsWithItsJob() = runTest {
        var ticks = 0
        val job =
            launch {
                PreparationProgressPoller(intervalMillis = 1_000).run {
                    ticks += 1
                }
            }

        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, ticks)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, ticks)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, ticks)

        job.cancelAndJoin()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, ticks)
    }
}
