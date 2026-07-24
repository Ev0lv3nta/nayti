package app.nayti.indexer

import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import app.nayti.storage.ModelPackEntity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class OcrIndexingStatus {
    Idle,
    Running,
    Paused,
    Waiting,
    Ready,
    Failed,
}

enum class SearchCapability {
    TEXT,
    MEANING,
    VISUAL,
    DUPLICATES,
}

data class SearchCapabilityCoverage(
    val capability: SearchCapability,
    val accessible: Long,
    val committed: Long,
    val permanentGaps: Long,
    val outstanding: Long,
)

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
    val capabilities: List<SearchCapabilityCoverage> = emptyList(),
    val activeDurationMillis: Long = 0,
    val estimatedAllMediaDurationMillis: Long? = null,
    val scope: IndexingScopeSummary =
        IndexingScopeSummary(
            mode = IndexingScopeMode.ALL,
            takenFromMillis = null,
            revision = 1,
            totalAvailable = 0,
            eligibleAssets = 0,
            unknownDateAssets = 0,
        ),
)

object OcrExecutionHost {
    const val AppForeground = "APP_FOREGROUND"
    const val UserForegroundService = "USER_FGS"
}

class IndexExecutionGate(internal val mutex: Mutex = Mutex()) {
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

class OcrIndexingRuntime(
    private val storage: CatalogStorage,
    private val packResolver: InstalledOcrPackResolver,
    private val decoder: BoundedMediaDecoder,
    private val vectorRoot: File,
    private val scope: CoroutineScope,
    private val clock: IndexExecutionClock =
        IndexExecutionClock { System.nanoTime() / 1_000_000L },
    private val resourceGovernor: IndexResourceGovernor =
        IndexResourceGovernor { IndexResourceDecision(canContinue = true) },
    private val neuralLane: NeuralExecutionLane = NeuralExecutionLane(),
    executionGate: IndexExecutionGate = IndexExecutionGate(),
) {
    private val running = AtomicBoolean(false)
    private val continueExecution = AtomicBoolean(false)
    private val activeHostType = AtomicReference<String?>(null)
    private val currentOperationId = AtomicReference<String?>(null)
    private val currentPack = AtomicReference<ModelPackEntity?>(null)
    private val executionMutex = executionGate.mutex
    private val mutableState = MutableStateFlow(EmptyState)

    val state: StateFlow<OcrIndexingState> = mutableState.asStateFlow()

    fun refresh(pack: ModelPackEntity?) {
        if (running.get()) return
        currentPack.set(pack)
        scope.launch {
            if (!running.get()) publishCoverage(pack, lastSlicePublished = 0)
        }
    }

    suspend fun setIndexingScope(takenFromMillis: Long?): Boolean {
        require(takenFromMillis == null || takenFromMillis >= 0)
        if (running.get() || !executionMutex.tryLock()) return false
        return try {
            if (running.get()) return false
            val current = storage.catalogDao.currentIndexingScope()
            val mode = if (takenFromMillis == null) IndexingScopeMode.ALL else IndexingScopeMode.SINCE_DATE
            if (current.mode != mode || current.takenFromMillis != takenFromMillis) {
                val now = System.currentTimeMillis()
                require(takenFromMillis == null || takenFromMillis <= now)
                storage.catalogDao.updateIndexingScope(mode, takenFromMillis, now)
                currentOperationId.set(null)
            }
            publishCoverage(currentPack.get(), lastSlicePublished = 0, operationId = null)
            true
        } finally {
            executionMutex.unlock()
        }
    }

    suspend fun runAppForeground(pack: ModelPackEntity): Boolean {
        if (!hasAutoResumableOperation(pack) || !executionMutex.tryLock()) return false
        return try {
            if (!hasAutoResumableOperation(pack)) return false
            runWindowsLocked(
                pack = pack,
                hostType = OcrExecutionHost.AppForeground,
                maximumWindows = 1,
                itemLimit = SliceItemLimit,
                maximumDurationMillis = ExecutionWindowMillis,
                initiator = IndexExecutionInitiator.Automatic,
            )
        } finally {
            executionMutex.unlock()
        }
    }

    suspend fun runForeground(pack: ModelPackEntity): Boolean {
        requestAppForegroundStop()
        return executionMutex.withLock {
            resumeForExplicitStart(pack)
            runWindowsLocked(
                pack = pack,
                hostType = OcrExecutionHost.UserForegroundService,
                maximumWindows = MaximumForegroundWindows,
                itemLimit = ForegroundWindowItemLimit,
                maximumDurationMillis = ForegroundExecutionBudgetMillis,
                initiator = IndexExecutionInitiator.Manual,
            )
        }
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

    suspend fun retryPermanentGaps(): Boolean = executionMutex.withLock {
        val pack = currentPack.get() ?: return@withLock false
        val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
        val operationId = mutableState.value.operationId ?: operationId(pack, catalogRevision)
        val operation = storage.indexStateDao.operation(operationId) ?: return@withLock false
        if (operation.state != IndexOperationState.COMPLETED_WITH_GAPS) return@withLock false
        storage.indexStateDao.retryPermanentGaps(operationId, System.currentTimeMillis())
        currentOperationId.set(operationId)
        publishCoverage(pack, lastSlicePublished = 0, operationId = operationId)
        true
    }

    suspend fun resume() {
        transitionAndPublish(IndexOperationState.PLANNED, autoResume = true)
    }

    suspend fun stopForSystem() {
        requestStop()
        transitionAndPublish(IndexOperationState.WAITING_SYSTEM, autoResume = true)
    }

    fun requestAppForegroundStop() {
        if (activeHostType.get() == OcrExecutionHost.AppForeground) requestStop()
    }

    private suspend fun runWindowsLocked(
        pack: ModelPackEntity,
        hostType: String,
        maximumWindows: Int,
        itemLimit: Int,
        maximumDurationMillis: Long,
        initiator: IndexExecutionInitiator,
    ): Boolean {
        require(maximumWindows > 0)
        require(itemLimit in 1..256)
        check(activeHostType.compareAndSet(null, hostType))
        running.set(true)
        currentPack.set(pack)
        continueExecution.set(true)
        val budget = IndexExecutionBudget(clock, maximumDurationMillis)
        val activeConstraint = AtomicReference<String?>(null)
        mutableState.value = mutableState.value.copy(
            status = OcrIndexingStatus.Running,
            errorCode = null,
            hostType = hostType,
        )
        val liveProgressJob =
            CoroutineScope(currentCoroutineContext()).launch {
                PreparationProgressPoller().run {
                    try {
                        publishCoverage(
                            pack = pack,
                            lastSlicePublished = 0,
                            operationId = currentOperationId.get(),
                            hostType = hostType,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // The durable work is authoritative; a best-effort UI projection must not stop it.
                    }
                }
            }
        var operationId: String? = null
        try {
            var windows = 0
            while (
                windows < maximumWindows &&
                    canContinue(budget, initiator, activeConstraint)
            ) {
                val result = executeWindow(pack, hostType, itemLimit, budget, initiator, activeConstraint)
                windows += 1
                operationId = result.operation.operationId
                currentOperationId.set(operationId)
                publishCoverage(pack, result.visualPublished, operationId, hostType)
                if (!result.saturated) break
            }
            val coverage = coverage(pack, storage.catalogDao.currentIndexingScope().takenFromMillis)
            val constraintCode = activeConstraint.get()
            if (coverage != null && coverage.outstandingAssetCount > 0) {
                if (constraintCode == null) {
                    if (continueExecution.get()) {
                        transitionCurrent(IndexOperationState.WAITING_SYSTEM, autoResume = true)
                    }
                } else {
                    transitionCurrent(IndexOperationState.PAUSED_CONSTRAINT, autoResume = true)
                }
            }
            running.set(false)
            publishCoverage(pack, lastSlicePublished = 0, operationId = operationId, hostType = null)
            if (constraintCode != null) {
                mutableState.value = mutableState.value.copy(errorCode = constraintCode)
            }
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
            liveProgressJob.cancel()
            continueExecution.set(false)
            running.set(false)
            check(activeHostType.compareAndSet(hostType, null))
        }
    }

    private suspend fun hasAutoResumableOperation(pack: ModelPackEntity): Boolean {
        val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
        val operation = storage.indexStateDao.operation(operationId(pack, catalogRevision)) ?: return false
        return AppForegroundExecutionPolicy.canResume(operation)
    }

    private suspend fun executeWindow(
        pack: ModelPackEntity,
        hostType: String,
        itemLimit: Int,
        budget: IndexExecutionBudget,
        initiator: IndexExecutionInitiator,
        activeConstraint: AtomicReference<String?>,
    ): WindowResult {
        val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
        val indexingScope = storage.catalogDao.currentIndexingScope()
        val planner =
            IndexExecutionCoordinator(
                indexState = storage.indexStateDao,
                catalog = storage.catalogDao,
                executors = emptyMap(),
            )
        planner.recoverExpiredExecution()
        val operation =
            planner.planOperation(
                IndexOperationRequest(
                    operationId = operationId(pack, catalogRevision, indexingScope.revision),
                    profileId = ProfileId,
                    targetPackId = pack.packId,
                    targetPackVersion = pack.packVersion,
                    channels =
                        listOf(
                            IndexChannelContract(
                                channel = IndexChannel.PHASH,
                                priority = 0,
                                pipelineVersion = PerceptualHashV1.PipelineVersion,
                                componentHash = PerceptualHashV1.ComponentHash,
                            ),
                            IndexChannelContract(
                                channel = IndexChannel.OCR,
                                priority = 1,
                                pipelineVersion = PipelineVersion,
                                componentHash = pack.manifestSha256,
                            ),
                            IndexChannelContract(
                                channel = IndexChannel.OCR_SEMANTIC,
                                priority = 2,
                                pipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                                componentHash = pack.manifestSha256,
                            ),
                            IndexChannelContract(
                                channel = IndexChannel.VISUAL,
                                priority = 3,
                                pipelineVersion = Siglip2Contract.PipelineVersion,
                                componentHash = pack.manifestSha256,
                            ),
                        ),
                    autoResume = true,
                ),
            )
        currentOperationId.set(operation.operationId)

        var report = EmptyReport
        var saturated = false
        if (
            canContinue(budget, initiator, activeConstraint) &&
            hasOutstanding(
                IndexChannel.PHASH,
                PerceptualHashV1.PipelineVersion,
                PerceptualHashV1.ComponentHash,
            )
        ) {
            val phase =
                runPhase(
                    operation = operation,
                    hostType = hostType,
                    itemLimit = itemLimit,
                    channel = IndexChannel.PHASH,
                    executor = PerceptualHashChannelExecutor(storage.perceptualHashDao, decoder),
                    budget = budget,
                    initiator = initiator,
                    activeConstraint = activeConstraint,
                )
            report = report.merge(phase)
            saturated = saturated || phase.claimed == itemLimit
        }
        if (
            canContinue(budget, initiator, activeConstraint) &&
            hasOutstanding(IndexChannel.OCR, PipelineVersion, pack.manifestSha256)
        ) {
            neuralLane.withPermit {
                OcrExecutionSession.open(
                    packId = pack.packId,
                    packVersion = pack.packVersion,
                    resolver = packResolver,
                    ocr = storage.ocrDao,
                    decoder = decoder,
                ).use { session ->
                    val phase =
                        runPhase(
                            operation = operation,
                            hostType = hostType,
                            itemLimit = itemLimit,
                            channel = IndexChannel.OCR,
                            executor = session.executor,
                            budget = budget,
                            initiator = initiator,
                            activeConstraint = activeConstraint,
                        )
                    report = report.merge(phase)
                    saturated = saturated || phase.claimed == itemLimit
                }
            }
        }
        if (
            canContinue(budget, initiator, activeConstraint) &&
            hasOutstanding(
                IndexChannel.OCR_SEMANTIC,
                OcrSemanticChannelExecutor.PipelineVersion,
                pack.manifestSha256,
            )
        ) {
            neuralLane.withPermit {
                OcrSemanticExecutionSession.open(
                    packId = pack.packId,
                    packVersion = pack.packVersion,
                    resolver = packResolver,
                    indexState = storage.indexStateDao,
                    semantic = storage.ocrSemanticDao,
                    vectors = storage.vectorIndexDao,
                    vectorRoot = vectorRoot,
                ).use { session ->
                    val phase =
                        runPhase(
                            operation = operation,
                            hostType = hostType,
                            itemLimit = itemLimit,
                            channel = IndexChannel.OCR_SEMANTIC,
                            executor = session.executor,
                            budget = budget,
                            initiator = initiator,
                            activeConstraint = activeConstraint,
                        )
                    report = report.merge(phase)
                    saturated = saturated || phase.claimed == itemLimit
                    if (phase.published > 0 && budget.hasTimeRemaining()) {
                        VectorIndexCompactor(vectorRoot, storage.vectorIndexDao)
                            .compactAvailable(MaximumCompactionsPerWindow)
                    }
                }
            }
        }
        var visualPublished = 0
        if (
            canContinue(budget, initiator, activeConstraint) &&
            hasOutstanding(
                IndexChannel.VISUAL,
                Siglip2Contract.PipelineVersion,
                pack.manifestSha256,
            )
        ) {
            neuralLane.withPermit {
                VisualExecutionSession.open(
                    packId = pack.packId,
                    packVersion = pack.packVersion,
                    resolver = packResolver,
                    indexState = storage.indexStateDao,
                    semantic = storage.ocrSemanticDao,
                    hashes = storage.perceptualHashDao,
                    vectors = storage.vectorIndexDao,
                    decoder = decoder,
                    vectorRoot = vectorRoot,
                ).use { session ->
                    val phase =
                        runPhase(
                            operation = operation,
                            hostType = hostType,
                            itemLimit = itemLimit,
                            channel = IndexChannel.VISUAL,
                            executor = session.executor,
                            budget = budget,
                            initiator = initiator,
                            activeConstraint = activeConstraint,
                        )
                    report = report.merge(phase)
                    visualPublished = phase.published
                    saturated = saturated || phase.claimed == itemLimit
                    if (phase.published > 0 && budget.hasTimeRemaining()) {
                        VectorIndexCompactor(vectorRoot, storage.vectorIndexDao)
                            .compactAvailable(MaximumCompactionsPerWindow, IndexChannel.VISUAL)
                    }
                }
            }
        }
        return WindowResult(operation, report, visualPublished, saturated)
    }

    private suspend fun runPhase(
        operation: IndexOperationEntity,
        hostType: String,
        itemLimit: Int,
        channel: String,
        executor: IndexChannelExecutor,
        budget: IndexExecutionBudget,
        initiator: IndexExecutionInitiator,
        activeConstraint: AtomicReference<String?>,
    ): IndexExecutionReport {
        val coordinator =
            IndexExecutionCoordinator(
                indexState = storage.indexStateDao,
                catalog = storage.catalogDao,
                executors = mapOf(channel to executor),
            )
        val window =
            coordinator.startExecutionWindow(
                operationId = operation.operationId,
                hostType = hostType,
                durationMillis = ExecutionWindowMillis,
            )
        return coordinator.runWindow(
            windowId = window.windowId,
            itemLimit = itemLimit,
            control = IndexExecutionControl {
                canContinue(budget, initiator, activeConstraint)
            },
            channelsToRun = setOf(channel),
        )
    }

    private suspend fun hasOutstanding(
        channel: String,
        pipelineVersion: String,
        componentHash: String,
    ): Boolean {
        val access = storage.catalogDao.accessObservation() ?: return false
        if (access.accessScope == "None") return false
        return storage.indexStateDao.channelCoverage(
            channel = channel,
            accessRevision = access.processAccessRevision,
            pipelineVersion = pipelineVersion,
            componentHash = componentHash,
            takenFromMillis = storage.catalogDao.currentIndexingScope().takenFromMillis,
        ).outstandingAssetCount > 0
    }

    private fun IndexExecutionReport.merge(other: IndexExecutionReport): IndexExecutionReport =
        IndexExecutionReport(
            claimed = Math.addExact(claimed, other.claimed),
            published = Math.addExact(published, other.published),
            retryableFailures = Math.addExact(retryableFailures, other.retryableFailures),
            permanentFailures = Math.addExact(permanentFailures, other.permanentFailures),
            leaseRejections = Math.addExact(leaseRejections, other.leaseRejections),
        )

    private fun canContinue(
        budget: IndexExecutionBudget,
        initiator: IndexExecutionInitiator,
        activeConstraint: AtomicReference<String?>,
    ): Boolean {
        if (!continueExecution.get() || !budget.hasTimeRemaining()) return false
        val decision = resourceGovernor.evaluate(initiator)
        if (!decision.canContinue) {
            activeConstraint.compareAndSet(null, decision.constraintCode ?: "RESOURCE_CONSTRAINT")
        }
        return decision.canContinue
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
        storage.indexStateDao.operation(operationId) ?: return
        currentOperationId.set(operationId)
        storage.indexStateDao.prepareOperationForExplicitStart(
            operationId = operationId,
            nowMillis = System.currentTimeMillis(),
        )
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
        val scopeSummary = storage.catalogDao.indexingScopeSummary()
        if (pack == null) {
            mutableState.value = EmptyState.copy(scope = scopeSummary)
            return
        }
        val access = storage.catalogDao.accessObservation()
        if (access == null || access.accessScope == "None") {
            mutableState.value = EmptyState.copy(scope = scopeSummary)
            return
        }
        val coverage = coverage(pack, scopeSummary.takenFromMillis) ?: return
        val selectedOperation =
            if (operationId == null) {
                val catalogRevision = storage.catalogDao.watermark()?.catalogRevision ?: 0
                storage.indexStateDao.operation(operationId(pack, catalogRevision))
            } else {
                storage.indexStateDao.operation(operationId)
            }
        val operation = selectedOperation?.takeIf { candidate ->
            candidate.targetPackId == pack.packId && candidate.targetPackVersion == pack.packVersion
        }
        if (operation != null) currentOperationId.set(operation.operationId)
        val activeDurationMillis =
            operation?.let { current ->
                storage.indexStateDao.operationActiveDurationMillis(current.operationId, System.currentTimeMillis())
            } ?: 0
        mutableState.value =
            coverage.toState(
                operation,
                lastSlicePublished,
                hostType,
                scopeSummary,
                activeDurationMillis,
            )
    }

    private suspend fun coverage(
        pack: ModelPackEntity,
        takenFromMillis: Long?,
    ): PreparationCoverage? {
        val access = storage.catalogDao.accessObservation() ?: return null
        if (access.accessScope == "None") return null
        suspend fun channel(
            capability: SearchCapability,
            channel: String,
            pipelineVersion: String,
            componentHash: String,
        ): SearchCapabilityCoverage {
            val coverage = storage.indexStateDao.channelCoverage(
                channel = channel,
                accessRevision = access.processAccessRevision,
                pipelineVersion = pipelineVersion,
                componentHash = componentHash,
                takenFromMillis = takenFromMillis,
            )
            return SearchCapabilityCoverage(
                capability = capability,
                accessible = coverage.accessibleAssetCount,
                committed = coverage.committedAssetCount,
                permanentGaps = coverage.permanentGapCount,
                outstanding = coverage.outstandingAssetCount,
            )
        }
        val capabilities = listOf(
            channel(
                SearchCapability.TEXT,
                IndexChannel.OCR,
                PipelineVersion,
                pack.manifestSha256,
            ),
            channel(
                SearchCapability.MEANING,
                IndexChannel.OCR_SEMANTIC,
                OcrSemanticChannelExecutor.PipelineVersion,
                pack.manifestSha256,
            ),
            channel(
                SearchCapability.VISUAL,
                IndexChannel.VISUAL,
                Siglip2Contract.PipelineVersion,
                pack.manifestSha256,
            ),
            channel(
                SearchCapability.DUPLICATES,
                IndexChannel.PHASH,
                PerceptualHashV1.PipelineVersion,
                PerceptualHashV1.ComponentHash,
            ),
        )
        return PreparationCoverage(
            completion = capabilities.first { it.capability == SearchCapability.VISUAL },
            capabilities = capabilities,
        )
    }

    private fun PreparationCoverage.toState(
        operation: IndexOperationEntity?,
        lastSlicePublished: Int,
        hostType: String?,
        scopeSummary: IndexingScopeSummary,
        activeDurationMillis: Long,
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
            accessible = completion.accessible,
            committed = completion.committed,
            permanentGaps = completion.permanentGaps,
            outstanding = completion.outstanding,
            lastSlicePublished = lastSlicePublished,
            errorCode = null,
            operationId = operation?.operationId,
            operationState = operation?.state,
            hostType = hostType,
            capabilities = capabilities,
            activeDurationMillis = activeDurationMillis,
            estimatedAllMediaDurationMillis =
                estimateAllMediaDuration(scopeSummary, capabilities, activeDurationMillis),
            scope = scopeSummary,
        )

    private suspend fun operationId(pack: ModelPackEntity, catalogRevision: Long): String =
        operationId(pack, catalogRevision, storage.catalogDao.currentIndexingScope().revision)

    private fun operationId(
        pack: ModelPackEntity,
        catalogRevision: Long,
        scopeRevision: Long,
    ): String =
        "search-v3-${pack.manifestSha256.take(16)}-catalog-$catalogRevision-scope-$scopeRevision"

    private data class WindowResult(
        val operation: IndexOperationEntity,
        val report: IndexExecutionReport,
        val visualPublished: Int,
        val saturated: Boolean,
    )

    private data class PreparationCoverage(
        val completion: SearchCapabilityCoverage,
        val capabilities: List<SearchCapabilityCoverage>,
    ) {
        val outstandingAssetCount: Long
            get() = completion.outstanding
    }

    companion object {
        const val PipelineVersion = "ocr-v1"
        const val SliceItemLimit = 8
        const val ForegroundWindowItemLimit = 256
        private const val MaximumForegroundWindows = 64
        private const val MaximumCompactionsPerWindow = 8
        private const val ForegroundExecutionBudgetMillis = 5L * 60 * 60 * 1_000
        private const val ProfileId = "balanced-v1"
        private const val ExecutionWindowMillis = 10L * 60 * 1_000
        private val EmptyReport = IndexExecutionReport(0, 0, 0, 0, 0)
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
                scope =
                    IndexingScopeSummary(
                        mode = IndexingScopeMode.ALL,
                        takenFromMillis = null,
                        revision = 1,
                        totalAvailable = 0,
                        eligibleAssets = 0,
                        unknownDateAssets = 0,
                    ),
            )
    }
}

internal fun estimateAllMediaDuration(
    scope: IndexingScopeSummary,
    capabilities: List<SearchCapabilityCoverage>,
    activeDurationMillis: Long,
): Long? {
    if (
        scope.mode == IndexingScopeMode.ALL ||
        scope.eligibleAssets <= 0 ||
        scope.totalAvailable <= scope.eligibleAssets ||
        activeDurationMillis <= 0 ||
        capabilities.isEmpty() ||
        capabilities.any { coverage -> coverage.outstanding > 0 }
    ) {
        return null
    }
    val scaled =
        activeDurationMillis.toDouble() *
            scope.totalAvailable.toDouble() /
            scope.eligibleAssets.toDouble()
    return scaled.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
}
