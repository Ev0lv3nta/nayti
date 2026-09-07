package app.nayti.indexer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceTransferTest {
    @Test
    fun callerOwnsSuccessfullyDeliveredResource() = runTest {
        val resource = Resource()
        val result = transferResource(StandardTestDispatcher(testScheduler)) { resource }
        assertSame(resource, result)
        assertEquals(0, resource.closes)
        result.close()
        assertEquals(1, resource.closes)
    }

    @Test
    fun closesResourceWhenCancellationWinsReturnToCaller() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val resource = Resource()
        var delivered = false
        lateinit var request: Job
        request = launch {
            transferResource(worker) {
                request.cancel()
                resource
            }
            delivered = true
        }
        runCurrent()
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(false, delivered)
        assertEquals(1, resource.closes)
    }

    private class Resource : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }
}
