package app.nayti.indexer

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NeuralExecutionLaneTest {
    @Test
    fun serializesOwnersAndMakesPermitCloseIdempotent() = runTest {
        val lane = NeuralExecutionLane()
        val first = lane.acquire()
        val second = async { lane.acquire() }
        runCurrent()
        assertFalse(second.isCompleted)

        first.close()
        first.close()
        runCurrent()

        assertTrue(second.isCompleted)
        second.await().close()
    }

    @Test
    fun failedBlockAlwaysReleasesLane() = runTest {
        val lane = NeuralExecutionLane()
        val expected = IllegalStateException("failed inference")

        val actual = runCatching { lane.withPermit { throw expected } }.exceptionOrNull()

        assertSame(expected, actual)
        lane.acquire().close()
    }

    @Test
    fun interactiveQueryRunsBeforeQueuedBackgroundIndexing() = runTest {
        val lane = NeuralExecutionLane()
        val activeIndexing = lane.acquire()
        val nextIndexing = async { lane.acquire(NeuralExecutionPriority.BACKGROUND_INDEXING) }
        val query = async { lane.acquire(NeuralExecutionPriority.INTERACTIVE_QUERY) }
        runCurrent()
        assertFalse(nextIndexing.isCompleted)
        assertFalse(query.isCompleted)

        activeIndexing.close()
        runCurrent()

        assertTrue(query.isCompleted)
        assertFalse(nextIndexing.isCompleted)
        query.await().close()
        runCurrent()
        assertTrue(nextIndexing.isCompleted)
        nextIndexing.await().close()
    }
}
