package app.nayti.indexer

import app.nayti.ml.runtime.pack.ModelPackSource
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ModelPackRuntimeStatus {
    Loading,
    Missing,
    Installing,
    Ready,
    Failed,
}

data class ModelPackRuntimeState(
    val status: ModelPackRuntimeStatus,
    val installed: ModelPackEntity?,
    val candidate: ModelPackEntity?,
    val errorCode: String?,
    val cancelRequested: Boolean = false,
)

class ModelPackRuntime(
    private val installer: RegisteredModelPackInstaller,
    private val registry: ModelPackDao,
    private val scope: CoroutineScope,
    private val activePack: suspend () -> ModelPackEntity? = { null },
) {
    private val installing = AtomicBoolean(false)
    private var installationJob: Job? = null
    private val mutableState =
        MutableStateFlow(ModelPackRuntimeState(ModelPackRuntimeStatus.Loading, null, null, null))

    val state: StateFlow<ModelPackRuntimeState> = mutableState.asStateFlow()

    fun start() {
        refresh()
    }

    fun refresh() {
        scope.launch { refreshState() }
    }

    fun install(source: ModelPackSource) {
        if (!installing.compareAndSet(false, true)) return
        val previous = mutableState.value
        mutableState.value = mutableState.value.copy(
            status = ModelPackRuntimeStatus.Installing, errorCode = null, cancelRequested = false,
        )
        val entered = AtomicBoolean(false)
        installationJob = scope.launch {
            entered.set(true)
            try {
                val installed = installer.install(source)
                val active = activePack()
                mutableState.value =
                    ModelPackRuntimeState(
                        status = ModelPackRuntimeStatus.Ready,
                        installed = active ?: installed,
                        candidate = installed.takeIf { active != null && it != active },
                        errorCode = null,
                    )
            } catch (cancellation: CancellationException) {
                mutableState.value = previous
                throw cancellation
            } catch (failure: Exception) {
                mutableState.value =
                    ModelPackRuntimeState(
                        status = ModelPackRuntimeStatus.Failed,
                        installed = activePack() ?: newestInstalled(),
                        candidate = null,
                        errorCode = failure::class.java.simpleName.uppercase(),
                    )
            } catch (_: LinkageError) {
                mutableState.value =
                    ModelPackRuntimeState(
                        status = ModelPackRuntimeStatus.Failed,
                        installed = activePack() ?: newestInstalled(),
                        candidate = null,
                        errorCode = "RUNTIME_UNAVAILABLE",
                    )
            } finally {
                installing.set(false)
            }
        }
        installationJob?.invokeOnCompletion { failure ->
            // A job cancelled before its first instruction never enters try/finally.
            if (!entered.get() && failure is CancellationException) {
                mutableState.value = previous
                installing.set(false)
            }
            if (failure is CancellationException) refresh()
        }
    }

    fun cancelInstall() {
        if (!installing.get()) return
        mutableState.value = mutableState.value.copy(cancelRequested = true)
        installationJob?.cancel()
    }

    private suspend fun refreshState() {
        if (installing.get()) return
        val active = activePack()
        val newest = newestInstalled()
        val installed = active ?: newest
        if (installing.get()) return
        mutableState.value =
            ModelPackRuntimeState(
                status = if (installed == null) ModelPackRuntimeStatus.Missing else ModelPackRuntimeStatus.Ready,
                installed = installed,
                candidate = newest.takeIf { active != null && it != active },
                errorCode = null,
            )
    }

    private suspend fun newestInstalled(): ModelPackEntity? =
        registry.packs()
            .asSequence()
            .filter { pack -> pack.status == ModelPackStatus.INSTALLED_CANDIDATE }
            .maxWithOrNull(
                compareBy<ModelPackEntity>(ModelPackEntity::installedAtMillis)
                    .thenBy(ModelPackEntity::packVersion),
            )
}
