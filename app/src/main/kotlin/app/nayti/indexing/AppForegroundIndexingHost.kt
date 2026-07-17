package app.nayti.indexing

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.OcrIndexingRuntime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class AppForegroundIndexingHost @Inject constructor(
    private val catalog: CatalogRuntime,
    private val modelPacks: ModelPackRuntime,
    private val indexing: OcrIndexingRuntime,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = AtomicBoolean(false)
    private var executionJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (!foreground.compareAndSet(false, true)) return
        executionJob =
            scope.launch {
                val (_, packState) =
                    combine(catalog.state, modelPacks.state) { catalogState, packState ->
                        catalogState to packState
                    }.first { (catalogState, packState) ->
                        catalogState.status == CatalogRuntimeStatus.Ready && packState.installed != null
                    }
                if (foreground.get()) indexing.runAppForeground(checkNotNull(packState.installed))
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!foreground.compareAndSet(true, false)) return
        indexing.requestAppForegroundStop()
        val job = executionJob
        executionJob = null
        job?.cancel()
    }
}
