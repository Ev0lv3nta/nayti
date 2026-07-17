package app.nayti.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.platform.media.MediaDecodeProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ViewerProbeState {
    data object Idle : ViewerProbeState

    data object Loading : ViewerProbeState

    data class Ready(val probe: MediaDecodeProbe) : ViewerProbeState

    data class Failed(val code: String) : ViewerProbeState
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val runtime: CatalogRuntime,
) : ViewModel() {
    val catalog: StateFlow<CatalogRuntimeState> = runtime.state

    private val mutableViewerProbe = MutableStateFlow<ViewerProbeState>(ViewerProbeState.Idle)
    val viewerProbe: StateFlow<ViewerProbeState> = mutableViewerProbe.asStateFlow()

    fun refresh(forceFull: Boolean = false) {
        runtime.refreshAccess(forceFull)
    }

    fun onPermissionResult() {
        runtime.onPermissionResult()
    }

    fun probe(assetId: Long) {
        val accessPin = catalog.value.access
        mutableViewerProbe.value = ViewerProbeState.Loading
        viewModelScope.launch {
            mutableViewerProbe.value =
                try {
                    ViewerProbeState.Ready(runtime.probe(assetId, accessPin))
                } catch (failure: Exception) {
                    ViewerProbeState.Failed(failure::class.java.simpleName.uppercase())
                }
        }
    }

    fun clearProbe() {
        mutableViewerProbe.value = ViewerProbeState.Idle
    }
}
