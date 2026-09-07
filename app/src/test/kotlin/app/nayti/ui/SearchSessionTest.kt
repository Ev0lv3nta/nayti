package app.nayti.ui

import app.nayti.indexer.OcrSemanticSearchStatus
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSessionTest {
    @Test
    fun newerRequestWinsEvenIfOldNativeOperationCannotStopImmediately() = runTest {
        val oldFinished = CompletableDeferred<Unit>()
        val session = SearchSession(backgroundScope) { request ->
            if (request.query == "old") withContext(NonCancellable) { oldFinished.await() }
            ready(request)
        }
        session.submit("old", SearchFilter.None, SearchChannelSelection.All)
        runCurrent()
        val filter = SearchFilter(bucketId = 42)
        session.submit("new", filter, SearchChannelSelection.All)
        runCurrent()
        val result = session.state.value as SearchUiState.Ready
        assertEquals("new", result.query)
        assertEquals(filter, result.filter)
        oldFinished.complete(Unit)
        runCurrent()
        assertEquals(result, session.state.value)
    }

    @Test
    fun cancellationReleasesOperationAndDoesNotPublishFailure() = runTest {
        var released = false
        val session = SearchSession(backgroundScope) {
            try { awaitCancellation() } finally { released = true }
        }
        session.submit("photo", SearchFilter.None, SearchChannelSelection.All)
        runCurrent()
        session.cancel()
        runCurrent()
        assertTrue(released)
        assertEquals(SearchUiState.Cancelled("photo"), session.state.value)
        session.clear()
        assertEquals(SearchUiState.Idle, session.state.value)
    }

    @Test
    fun emptyAndOversizedRequestsNeverReachEngine() = runTest {
        var calls = 0
        val session = SearchSession(backgroundScope) { calls++; ready(it) }
        session.submit("  ", SearchFilter.None, SearchChannelSelection.All)
        runCurrent()
        assertEquals(SearchUiState.Idle, session.state.value)
        session.submit("x".repeat(513), SearchFilter.None, SearchChannelSelection.All)
        runCurrent()
        assertEquals(SearchUiState.Failed("QUERY_TOO_LONG"), session.state.value)
        assertEquals(0, calls)
        session.submit("x".repeat(512), SearchFilter.None, SearchChannelSelection.All)
        runCurrent()
        assertEquals(1, calls)
    }

    private fun ready(request: SearchRequest) = SearchUiState.Ready(
        request.query, request.filter, emptyList(), request.channels,
        OcrSemanticSearchStatus.NOT_REQUESTED, null,
    )
}
