package app.nayti.indexer

import app.nayti.ml.runtime.pack.ModelPackSource
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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
    val errorCode: String?,
)

class ModelPackRuntime(
    private val installer: RegisteredModelPackInstaller,
    private val registry: ModelPackDao,
    private val scope: CoroutineScope,
) {
    private val installing = AtomicBoolean(false)
    private val mutableState =
        MutableStateFlow(ModelPackRuntimeState(ModelPackRuntimeStatus.Loading, null, null))

    val state: StateFlow<ModelPackRuntimeState> = mutableState.asStateFlow()

    fun start() {
        scope.launch { refresh() }
    }

    fun install(source: ModelPackSource) {
        if (!installing.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(status = ModelPackRuntimeStatus.Installing, errorCode = null)
        scope.launch {
            try {
                val installed = installer.install(source)
                mutableState.value = ModelPackRuntimeState(ModelPackRuntimeStatus.Ready, installed, null)
            } catch (failure: Exception) {
                mutableState.value =
                    ModelPackRuntimeState(
                        status = ModelPackRuntimeStatus.Failed,
                        installed = newestInstalled(),
                        errorCode = failure::class.java.simpleName.uppercase(),
                    )
            } catch (_: LinkageError) {
                mutableState.value =
                    ModelPackRuntimeState(
                        status = ModelPackRuntimeStatus.Failed,
                        installed = newestInstalled(),
                        errorCode = "RUNTIME_UNAVAILABLE",
                    )
            } finally {
                installing.set(false)
            }
        }
    }

    private suspend fun refresh() {
        val installed = newestInstalled()
        mutableState.value =
            ModelPackRuntimeState(
                status = if (installed == null) ModelPackRuntimeStatus.Missing else ModelPackRuntimeStatus.Ready,
                installed = installed,
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
