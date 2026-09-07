package app.nayti.ui

import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SearchRequest(
    val query: String,
    val filter: SearchFilter,
    val channels: SearchChannelSelection,
)

internal fun SearchUiState.submittedRequest(): SearchRequest? = when (this) {
    is SearchUiState.Ready -> SearchRequest(query, filter, channels)
    is SearchUiState.Searching -> SearchRequest(query, filter, channels)
    else -> null
}

/** Owns the submitted request; editing a UI draft does not relabel existing results. */
internal class SearchSession(
    private val scope: CoroutineScope,
    private val execute: suspend (SearchRequest) -> SearchUiState,
) {
    private val mutableState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var submitted: SearchRequest? = null

    fun submit(query: String, filter: SearchFilter, channels: SearchChannelSelection) {
        clear()
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        if (normalized.length > MaximumQueryCharacters) {
            mutableState.value = SearchUiState.Failed("QUERY_TOO_LONG")
            return
        }
        val request = SearchRequest(normalized, filter, channels)
        submitted = request
        val token = generation
        mutableState.value = SearchUiState.Searching(normalized, filter, channels)
        job = scope.launch {
            val result = try {
                execute(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                SearchUiState.Failed(failure::class.java.simpleName.uppercase())
            }
            currentCoroutineContext().ensureActive()
            if (generation == token) mutableState.value = result
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        submitted = null
        mutableState.value = SearchUiState.Idle
    }

    fun cancel() {
        val query = submitted?.query.orEmpty()
        clear()
        if (query.isNotBlank()) mutableState.value = SearchUiState.Cancelled(query)
    }

    companion object {
        const val MaximumQueryCharacters = 512
    }
}
