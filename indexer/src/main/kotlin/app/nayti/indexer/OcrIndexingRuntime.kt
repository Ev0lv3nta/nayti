package app.nayti.indexer

import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelCoverage
import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState
import app.nayti.storage.ModelPackEntity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OcrIndexingStatus {
    Idle,
    Running,
    Paused,
    Waiting,
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
    val operationId: String? = null,
    val operationState: String? = null,
    val hostType: String? = null,
)

object OcrExecutionHost {
    const val AppForeground = "APP_FOREGROUND"
    const val UserForegroundService = "USER_FGS"
}

class OcrIndexingRuntime(
    private val storage: CatalogStorage,
    private val packResolver: InstalledOcrPackResolver,
    private val decoder: BoundedMediaDecoder,
    private val scope: CoroutineScope,
    private val clock: IndexExecutionClock =
        IndexExecutionClock { System.nanoTime() / 1_000_000L },
) {
    private val running = AtomicBoolean(false)
    private val continueExecution = AtomicBoolean(false)
    private val currentOperationId = AtomicReference<String?>(null)
    private val currentPack = AtomicReference<ModelPackEntity?>(null)
    private val mutableState = MutableStateFlow(EmptyState)

    val state: StateFlow<OcrIndexingState> = mutableState.asStateFlow()

    fun refresh(pack: ModelPackEntity?) {
        if (running.get()) return
        currentPack.set(pack)
        scope.launch {
            if (!running.get()) publishCoverage(pack, lastSlicePublished = 0)
        }
    }

    fun runSlice(pack: ModelPackEntity) {
        scope.launch {
            runWindows(
                pack = pack,
                hostType = OcrExecutionHost.AppForeground,
                maximumWindows = 1,
                itemLimit = SliceItemLimit,
                maximumDurationMillis = ExecutionWindowMillis,
            )
        }
    }

    suspend fun runForeground(pack: ModelPackEntity): Boolean {
        resumeForExplicitStart(pack)
        return runWindows(
            pack = pack,
            hostType = OcrExecutionHost.UserForegroundService,
            maximumWindows = MaximumForegroundWindows,
            itemLimit = ForegroundWindowItemLimit,
            maximumDurationMillis = ForegroundExecutionBudgetMillis,
        )
    }

    suspend fun pause() {
        requestStop()
        transitionAndPublish(IndexOperationState.PAUSED_USER, autoResume = false)
    }

    suspend fun stopForNow() {
        requestStop()
        transitionAndPublish(IndexOperationState.WAITING_SYSTEM, autoResume = false)
    }

    suspend fun cancel() {
        requestStop()
        transitionAndPublish(IndexOperationState.CANCELLED, autoResume = false)
    }

    suspend fun resume() {
        transitionAndPublish(IndexOperationState.PLANNED, autoResume = true)
    }

    fun requestSystemStop() {
        requestStop()
        scope.launch {
            transitionAndPublish(IndexOperationState.WAITING_SYSTEM, autoResume = true)
        }
    }

    private suspend fun runWindows(
        pack: ModelPackEntity,
        hostType: String,
        maximumWindows: Int,
        itemLimit: Int,
        maximumDurationMillis: Long,
    ): Boolean {
        require(maximumWindows > 0)
        require(itemLimit in 1..256)
        if (!running.compareAndSet(false, true)) return false
        currentPack.set(pack)
        continueExecution.set(true)
        val budget = IndexExecutionBudget(clock, maximumDurationMillis)
        mutableState.value = mutableState.value.copy(
            status = OcrIndexingStatus.Running,
            errorCode = null,
            hostType = hostType,
        )
        var operationId: String? = null
        try {
            var windows = 0
            while (windows < maximumWindows && continueExecution.get() && budget.hasTimeRemaining()) {
                val result = executeWindow(pack, hostType, itemLimit, budget)
                windows += 1
                operationId = result.operation.operationId
                currentOperationId.set(operationId)
                publishCoverage(pack, result.report.published, operationId, hostType)
                if (result.report.claimed == 0 || result.report.claimed < itemLimit) break
            }
            val coverage = coverage(pack)
            if (continueExecution.get() && coverage != null && coverage.outstandingAssetCount > 0) {
                transitionCurrent(IndexOperationState.WAITING_SYSTEM, autoResume = true)
            }
            running.set(false)
            publishCoverage(pack, lastSlicePublished = 0, operationId = operationId, hostType = null)
            return true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            transitionConstraintAfterFailure()
            mutableState.value =
                mutableState.value.copy(
                    status = OcrIndexingStatus.Failed,
                    errorCode = failure::class.java.simpleName.uppercase(),
                    hostType = null,
                )
            return true
        } catch (_: LinkageError) {
            transitionConstraintAfterFailure()
            mutableState.value =
                mutableState.value.copy(
                    status = OcrIndexingStatus.Failed,
                    errorCode = "RUNTIME_UNAVAILABLE",
                    hostType = null,
                )
            return true
        } finally {
            continueExecution.set(false)
            running.set(false)
        }
    }

    private suspend fun executeWindow(
        pack: ModelPackEntity,
        hostType: String,
        itemLimit: Int,
        budget: IndexExecutionBudget,
    ): WindowResult =
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
            currentOperationId.set(operation.operationId)
            val window =
                coordinator.startExecutionWindow(
                    operationId = operation.operationId,
                    hostType = hostType,
                    durationMillis = ExecutionWindowMillis,
                )
            val report =
                coordinator.runWindow(
                    windowId = window.windowId,
                    itemLimit = itemLimit,
                    control = IndexExecutionControl {
                        continueExecution.get() && budget.hasTimeRemaining()
                    },
                )
            WindowResult(operation, report)
        }

    private fun requestStop() {
        continueExecution.set(false)
    }

    private suspend fun transitionCurrent(state: String, autoResume: Boolean): IndexOperationEntity? {
        val operationId = currentOperationId.get() ?: mutableState.value.operationId ?: return null
        val current = storage.indexStateDao.operation(operationId) ?: return null
        if (current.state in TerminalStates) return current
        return storage.indexStateDao.transitionOperation(
            operationId = operationId,
            state = state,
            autoResume = autoResume,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun transitionAndPublish(state: String, autoResume: Boolean) {
        val operation = transitionCurrent(state, autoResume)
        val pack = currentPack.get()
        if (pack != null) {
            publishCoverage(
                pack = pack,
                lastSlicePublished = 0,
                operationId = operation?.operationId,
                hostType = null,
            )
        }
    }

    private suspend fun resumeForExplicitStart(pack: ModelPackEntity) {
        val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
        val operationId = operationId(pack, catalogRevision)
        val operation = storage.indexStateDao.operation(operationId) ?: return
        currentOperationId.set(operationId)
        if (
            operation.state == IndexOperationState.PAUSED_USER ||
            operation.state == IndexOperationState.PAUSED_CONSTRAINT ||
            operation.state == IndexOperationState.WAITING_SYSTEM
        ) {
            storage.indexStateDao.transitionOperation(
                operationId = operationId,
                state = IndexOperationState.PLANNED,
                autoResume = true,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun transitionConstraintAfterFailure() {
        runCatching {
            transitionCurrent(IndexOperationState.PAUSED_CONSTRAINT, autoResume = true)
        }
    }

    private suspend fun publishCoverage(
        pack: ModelPackEntity?,
        lastSlicePublished: Int,
        operationId: String? = currentOperationId.get(),
        hostType: String? = null,
    ) {
        if (pack == null) {
            mutableState.value = EmptyState
            return
        }
        val access = storage.catalogDao.accessObservation()
        if (access == null || access.accessScope == "None") {
            mutableState.value = EmptyState
            return
        }
        val coverage = coverage(pack) ?: return
        val selectedOperation =
            if (operationId == null) {
                storage.indexStateDao.latestOperation(pack.packId, pack.packVersion)
            } else {
                storage.indexStateDao.operation(operationId)
            }
        val operation = selectedOperation?.takeIf { candidate ->
            candidate.targetPackId == pack.packId && candidate.targetPackVersion == pack.packVersion
        }
        if (operation != null) currentOperationId.set(operation.operationId)
        mutableState.value = coverage.toState(operation, lastSlicePublished, hostType)
    }

    private suspend fun coverage(pack: ModelPackEntity): IndexChannelCoverage? {
        val access = storage.catalogDao.accessObservation() ?: return null
        if (access.accessScope == "None") return null
        return storage.indexStateDao.channelCoverage(
            channel = IndexChannel.OCR,
            accessRevision = access.processAccessRevision,
            pipelineVersion = PipelineVersion,
            componentHash = pack.manifestSha256,
        )
    }

    private fun IndexChannelCoverage.toState(
        operation: IndexOperationEntity?,
        lastSlicePublished: Int,
        hostType: String?,
    ): OcrIndexingState =
        OcrIndexingState(
            status =
                when {
                    operation?.state == IndexOperationState.PAUSED_USER -> OcrIndexingStatus.Paused
                    operation?.state == IndexOperationState.PAUSED_CONSTRAINT ||
                        operation?.state == IndexOperationState.WAITING_SYSTEM -> OcrIndexingStatus.Waiting
                    operation?.state == IndexOperationState.CANCELLED -> OcrIndexingStatus.Ready
                    running.get() -> OcrIndexingStatus.Running
                    else -> OcrIndexingStatus.Ready
                },
            accessible = accessibleAssetCount,
            committed = committedAssetCount,
            permanentGaps = permanentGapCount,
            outstanding = outstandingAssetCount,
            lastSlicePublished = lastSlicePublished,
            errorCode = null,
            operationId = operation?.operationId,
            operationState = operation?.state,
            hostType = hostType,
        )

    private fun operationId(pack: ModelPackEntity, catalogRevision: Long): String =
        "ocr-${pack.manifestSha256.take(16)}-catalog-$catalogRevision"

    private data class WindowResult(
        val operation: IndexOperationEntity,
        val report: IndexExecutionReport,
    )

    companion object {
        const val PipelineVersion = "ocr-v1"
        const val SliceItemLimit = 8
        const val ForegroundWindowItemLimit = 256
        private const val MaximumForegroundWindows = 64
        private const val ForegroundExecutionBudgetMillis = 5L * 60 * 60 * 1_000
        private const val ProfileId = "balanced-v1"
        private const val ExecutionWindowMillis = 10L * 60 * 1_000
        private val TerminalStates =
            setOf(
                IndexOperationState.COMPLETED,
                IndexOperationState.COMPLETED_WITH_GAPS,
                IndexOperationState.CANCELLED,
            )
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
