package app.nayti.indexer

import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelCoverage
import app.nayti.storage.ModelPackEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OcrIndexingStatus {
    Idle,
    Running,
    Ready,
    Failed,
}

data class OcrIndexingState(
    val status: OcrIndexingStatus,
    val accessible: Long,
    val committed: Long,
    val permanentGaps: Long,
    val outstanding: Long,
    val lastSlicePublished: Int,
    val errorCode: String?,
)

class OcrIndexingRuntime(
    private val storage: CatalogStorage,
    private val packResolver: InstalledOcrPackResolver,
    private val decoder: BoundedMediaDecoder,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(EmptyState)

    val state: StateFlow<OcrIndexingState> = mutableState.asStateFlow()

    fun refresh(pack: ModelPackEntity?) {
        if (running.get()) return
        scope.launch {
            if (!running.get()) publishCoverage(pack, lastSlicePublished = 0)
        }
    }

    fun runSlice(pack: ModelPackEntity) {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(status = OcrIndexingStatus.Running, errorCode = null)
        scope.launch {
            try {
                OcrExecutionSession.open(
                    packId = pack.packId,
                    packVersion = pack.packVersion,
                    resolver = packResolver,
                    ocr = storage.ocrDao,
                    decoder = decoder,
                ).use { session ->
                    val coordinator =
                        IndexExecutionCoordinator(
                            indexState = storage.indexStateDao,
                            catalog = storage.catalogDao,
                            executors = mapOf(IndexChannel.OCR to session.executor),
                        )
                    coordinator.recoverExpiredExecution()
                    val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
                    val request =
                        IndexOperationRequest(
                            operationId = operationId(pack, catalogRevision),
                            profileId = ProfileId,
                            targetPackId = pack.packId,
                            targetPackVersion = pack.packVersion,
                            channels =
                                listOf(
                                    IndexChannelContract(
                                        channel = IndexChannel.OCR,
                                        priority = 0,
                                        pipelineVersion = PipelineVersion,
                                        componentHash = session.pack.componentHash,
                                    ),
                                ),
                            autoResume = true,
                        )
                    val operation = coordinator.planOperation(request)
                    val window =
                        coordinator.startExecutionWindow(
                            operationId = operation.operationId,
                            hostType = AppForegroundHost,
                            durationMillis = ExecutionWindowMillis,
                        )
                    val report = coordinator.runWindow(window.windowId, itemLimit = SliceItemLimit)
                    publishCoverage(pack, report.published)
                }
            } catch (failure: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        status = OcrIndexingStatus.Failed,
                        errorCode = failure::class.java.simpleName.uppercase(),
                    )
            } catch (_: LinkageError) {
                mutableState.value =
                    mutableState.value.copy(
                        status = OcrIndexingStatus.Failed,
                        errorCode = "RUNTIME_UNAVAILABLE",
                    )
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun publishCoverage(pack: ModelPackEntity?, lastSlicePublished: Int) {
        if (pack == null) {
            mutableState.value = EmptyState
            return
        }
        val access = storage.catalogDao.accessObservation()
        if (access == null || access.accessScope == "None") {
            mutableState.value = EmptyState
            return
        }
        val coverage =
            storage.indexStateDao.channelCoverage(
                channel = IndexChannel.OCR,
                accessRevision = access.processAccessRevision,
                pipelineVersion = PipelineVersion,
                componentHash = pack.manifestSha256,
            )
        mutableState.value = coverage.toState(lastSlicePublished)
    }

    private fun IndexChannelCoverage.toState(lastSlicePublished: Int): OcrIndexingState =
        OcrIndexingState(
            status = OcrIndexingStatus.Ready,
            accessible = accessibleAssetCount,
            committed = committedAssetCount,
            permanentGaps = permanentGapCount,
            outstanding = outstandingAssetCount,
            lastSlicePublished = lastSlicePublished,
            errorCode = null,
        )

    private fun operationId(pack: ModelPackEntity, catalogRevision: Long): String =
        "ocr-${pack.manifestSha256.take(16)}-catalog-$catalogRevision"

    companion object {
        const val PipelineVersion = "ocr-v1"
        const val SliceItemLimit = 8
        private const val ProfileId = "balanced-v1"
        private const val AppForegroundHost = "APP_FOREGROUND"
        private const val ExecutionWindowMillis = 10L * 60 * 1_000
        private val EmptyState =
            OcrIndexingState(
                status = OcrIndexingStatus.Idle,
                accessible = 0,
                committed = 0,
                permanentGaps = 0,
                outstanding = 0,
                lastSlicePublished = 0,
                errorCode = null,
            )
    }
}
