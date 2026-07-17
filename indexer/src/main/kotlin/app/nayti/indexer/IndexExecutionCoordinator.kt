package app.nayti.indexer

import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogDao
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexExecutionWindowEntity
import app.nayti.storage.IndexExecutionWindowState
import app.nayti.storage.IndexErrorLedgerEntity
import app.nayti.storage.IndexErrorScope
import app.nayti.storage.IndexOperationAssetEntity
import app.nayti.storage.IndexOperationChannelEntity
import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState
import app.nayti.storage.IndexStateDao
import app.nayti.storage.IndexWorkState
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class IndexChannelContract(
    val channel: String,
    val priority: Int,
    val pipelineVersion: String,
    val componentHash: String,
)

data class IndexOperationRequest(
    val operationId: String,
    val profileId: String,
    val targetPackId: String,
    val targetPackVersion: String,
    val channels: List<IndexChannelContract>,
    val autoResume: Boolean,
)

data class IndexClaimContext(
    val work: IndexChannelWorkEntity,
    val publicationToken: String,
)

sealed interface IndexExecutionOutcome {
    data object Published : IndexExecutionOutcome
    data object LeaseRejected : IndexExecutionOutcome
    data class Retryable(val errorCode: String) : IndexExecutionOutcome
    data class Permanent(val errorCode: String) : IndexExecutionOutcome
}

fun interface IndexChannelExecutor {
    suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome
}

fun interface IndexCoordinatorClock {
    fun nowMillis(): Long
}

fun interface IndexIdFactory {
    fun create(purpose: String): String
}

fun interface IndexExecutionControl {
    fun shouldContinue(): Boolean
}

data class IndexExecutionReport(
    val claimed: Int,
    val published: Int,
    val retryableFailures: Int,
    val permanentFailures: Int,
    val leaseRejections: Int,
)

class IndexExecutionCoordinator(
    private val indexState: IndexStateDao,
    private val catalog: CatalogDao,
    private val executors: Map<String, IndexChannelExecutor>,
    private val clock: IndexCoordinatorClock = IndexCoordinatorClock(System::currentTimeMillis),
    private val ids: IndexIdFactory = IndexIdFactory { purpose -> "$purpose-${UUID.randomUUID()}" },
) {
    suspend fun planOperation(request: IndexOperationRequest): IndexOperationEntity {
        validateRequest(request)
        val existing = indexState.operation(request.operationId)
        val operation =
            if (existing == null) {
                createOperation(request)
            } else {
                validateExistingOperation(existing, request)
                existing
            }
        reconcilePlannedWork(operation)
        return operation
    }

    suspend fun startExecutionWindow(
        operationId: String,
        hostType: String,
        durationMillis: Long,
    ): IndexExecutionWindowEntity {
        require(hostType.matches(HostType))
        require(durationMillis in 1..IndexStateDao.MaximumLeaseDurationMillis)
        val now = clock.nowMillis()
        val window =
            IndexExecutionWindowEntity(
                windowId = ids.create("window"),
                operationId = operationId,
                hostType = hostType,
                leaseToken = ids.create("window-lease"),
                state = IndexExecutionWindowState.RUNNING,
                startedAtMillis = now,
                expiresAtMillis = Math.addExact(now, durationMillis),
                finishedAtMillis = null,
            )
        indexState.startExecutionWindow(window, now)
        return window
    }

    suspend fun runWindow(
        windowId: String,
        itemLimit: Int = DefaultWindowItemLimit,
        control: IndexExecutionControl = ContinueExecution,
        channelsToRun: Set<String>? = null,
    ): IndexExecutionReport {
        require(itemLimit in 1..MaximumWindowItemLimit)
        val window = checkNotNull(indexState.executionWindow(windowId))
        check(window.state == IndexExecutionWindowState.RUNNING)
        val operationChannels = indexState.operationChannels(window.operationId)
        check(operationChannels.isNotEmpty())
        val selectedChannels = channelsToRun ?: operationChannels.mapTo(mutableSetOf(), IndexOperationChannelEntity::channel)
        require(selectedChannels.isNotEmpty() && selectedChannels.all { it in IndexChannel.all })
        val channels = operationChannels.filter { target -> target.channel in selectedChannels }
        require(channels.mapTo(mutableSetOf(), IndexOperationChannelEntity::channel) == selectedChannels) {
            "Execution channels are not part of the operation"
        }
        val missing = channels.map(IndexOperationChannelEntity::channel).filterNot(executors::containsKey)
        check(missing.isEmpty()) { "No executor for channels: ${missing.joinToString()}" }

        var claimedCount = 0
        var published = 0
        var retryable = 0
        var permanent = 0
        var rejected = 0
        try {
            while (claimedCount < itemLimit && control.shouldContinue()) {
                var madeProgress = false
                for (target in channels) {
                    if (!control.shouldContinue()) break
                    val remaining = itemLimit - claimedCount
                    if (remaining == 0) break
                    val now = clock.nowMillis()
                    val claims =
                        indexState.claimBatch(
                            windowId = windowId,
                            channel = target.channel,
                            claimNonce = ids.create("claim"),
                            nowMillis = now,
                            leaseDurationMillis = DefaultItemLeaseMillis,
                            limit = minOf(DefaultClaimBatchSize, remaining),
                        )
                    if (claims.isEmpty()) continue
                    madeProgress = true
                    claimedCount += claims.size
                    val executor = checkNotNull(executors[target.channel])
                    for (work in claims) {
                        if (!control.shouldContinue()) break
                        val context = IndexClaimContext(work, ids.create("publication"))
                        when (val outcome = executor.execute(context)) {
                            IndexExecutionOutcome.Published -> {
                                val committed = checkNotNull(indexState.work(work.assetId, work.channel))
                                check(committed.state == IndexWorkState.DONE)
                                check(committed.publicationToken == context.publicationToken)
                                indexState.resolveItemErrors(work.assetId, work.channel, clock.nowMillis())
                                published += 1
                            }
                            IndexExecutionOutcome.LeaseRejected -> rejected += 1
                            is IndexExecutionOutcome.Retryable -> {
                                val failureState =
                                    indexState.recordFailure(
                                        leaseToken = checkNotNull(work.leaseToken),
                                        errorCode = outcome.errorCode,
                                        nextEligibleAtMillis = retryAt(work.attempt, clock.nowMillis()),
                                        nowMillis = clock.nowMillis(),
                                    )
                                when (failureState) {
                                    IndexWorkState.PERMANENT_ERROR -> permanent += 1
                                    IndexWorkState.RETRYABLE_ERROR -> retryable += 1
                                    null -> rejected += 1
                                    else -> error("Unexpected failure state: $failureState")
                                }
                                if (failureState != null) {
                                    recordItemError(
                                        window,
                                        work,
                                        outcome.errorCode,
                                        failureState != IndexWorkState.PERMANENT_ERROR,
                                    )
                                }
                            }
                            is IndexExecutionOutcome.Permanent -> {
                                val nowMillis = clock.nowMillis()
                                val failureState =
                                    indexState.recordFailure(
                                        leaseToken = checkNotNull(work.leaseToken),
                                        errorCode = outcome.errorCode,
                                        nextEligibleAtMillis = nowMillis,
                                        nowMillis = nowMillis,
                                        maximumAttempts = 1,
                                    )
                                if (failureState == IndexWorkState.PERMANENT_ERROR) permanent += 1 else rejected += 1
                                if (failureState != null) {
                                    recordItemError(window, work, outcome.errorCode, retryable = false)
                                }
                            }
                        }
                    }
                }
                if (!madeProgress) break
            }
        } catch (failure: CancellationException) {
            indexState.stopExecutionWindow(
                windowId,
                IndexExecutionWindowState.CANCELLED,
                clock.nowMillis(),
            )
            throw failure
        }
        indexState.stopExecutionWindow(
            windowId,
            IndexExecutionWindowState.FINISHED,
            clock.nowMillis(),
        )
        indexState.refreshOperationTerminalState(window.operationId, clock.nowMillis())
        return IndexExecutionReport(claimedCount, published, retryable, permanent, rejected)
    }

    suspend fun recoverExpiredExecution(): Pair<Int, Int> =
        indexState.recoverExpiredExecution(clock.nowMillis())

    private suspend fun createOperation(request: IndexOperationRequest): IndexOperationEntity {
        val now = clock.nowMillis()
        val capturedAssets = catalog.availableAssets()
        val catalogRevision = catalog.watermark()?.catalogRevision ?: 0
        val operation =
            IndexOperationEntity(
                operationId = request.operationId,
                profileId = request.profileId,
                targetPackId = request.targetPackId,
                targetPackVersion = request.targetPackVersion,
                denominatorCatalogRevision = catalogRevision,
                denominatorAssetCount = capturedAssets.size.toLong(),
                state = IndexOperationState.PLANNED,
                autoResume = request.autoResume,
                createdAtMillis = now,
                updatedAtMillis = now,
                completedAtMillis = null,
            )
        val channels =
            request.channels.map { contract ->
                IndexOperationChannelEntity(
                    operationId = request.operationId,
                    channel = contract.channel,
                    priority = contract.priority,
                    pipelineVersion = contract.pipelineVersion,
                    componentHash = contract.componentHash,
                )
            }
        val assets =
            capturedAssets.map { asset ->
                IndexOperationAssetEntity(request.operationId, asset.assetId, asset.sourceFingerprint)
            }
        indexState.createOperation(operation, channels, assets)
        return operation
    }

    private suspend fun reconcilePlannedWork(operation: IndexOperationEntity) {
        val access = checkNotNull(catalog.accessObservation())
        check(access.accessScope != "None")
        val currentAssets = catalog.availableAssets().associateBy { asset -> asset.assetId }
        val captured = indexState.operationAssets(operation.operationId)
        val eligibleIds = captured.mapNotNull { asset -> currentAssets[asset.assetId]?.assetId }
        indexState.operationChannels(operation.operationId).forEach { target ->
            eligibleIds.chunked(IndexStateDao.MaximumBatchSize).forEach { batch ->
                indexState.ensureWorkBatch(
                    assetIds = batch,
                    channel = target.channel,
                    accessRevision = access.processAccessRevision,
                    pipelineVersion = target.pipelineVersion,
                    componentHash = target.componentHash,
                    nowMillis = clock.nowMillis(),
                )
            }
        }
    }

    private suspend fun validateExistingOperation(
        existing: IndexOperationEntity,
        request: IndexOperationRequest,
    ) {
        check(existing.profileId == request.profileId)
        check(existing.targetPackId == request.targetPackId)
        check(existing.targetPackVersion == request.targetPackVersion)
        check(existing.autoResume == request.autoResume)
        val actualChannels = indexState.operationChannels(existing.operationId)
        val requestedChannels =
            request.channels.map { channel ->
                IndexOperationChannelEntity(
                    existing.operationId,
                    channel.channel,
                    channel.priority,
                    channel.pipelineVersion,
                    channel.componentHash,
                )
            }.sortedBy(IndexOperationChannelEntity::priority)
        check(actualChannels == requestedChannels)
        check(indexState.operationAssets(existing.operationId).size.toLong() == existing.denominatorAssetCount)
    }

    private fun validateRequest(request: IndexOperationRequest) {
        require(request.channels.isNotEmpty())
        require(request.channels.all { contract ->
            contract.channel in IndexChannel.all &&
                contract.priority >= 0 &&
                contract.pipelineVersion.isNotBlank() &&
                Sha256.matches(contract.componentHash)
        })
        require(request.channels.map(IndexChannelContract::channel).toSet().size == request.channels.size)
        require(request.channels.map(IndexChannelContract::priority).toSet().size == request.channels.size)
    }

    private fun retryAt(attempt: Int, nowMillis: Long): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        val delay = minOf(MaximumRetryDelayMillis, BaseRetryDelayMillis shl shift)
        return Math.addExact(nowMillis, delay)
    }

    private suspend fun recordItemError(
        window: IndexExecutionWindowEntity,
        work: IndexChannelWorkEntity,
        errorCode: String,
        retryable: Boolean,
    ) {
        val now = clock.nowMillis()
        indexState.recordLedgerError(
            IndexErrorLedgerEntity(
                errorKey = "item:${window.operationId}:${work.assetId}:${work.channel}:$errorCode",
                scope = IndexErrorScope.ITEM,
                operationId = window.operationId,
                executionWindowId = null,
                assetId = work.assetId,
                channel = work.channel,
                code = errorCode,
                retryable = retryable,
                occurrenceCount = 1,
                firstSeenAtMillis = now,
                lastSeenAtMillis = now,
                resolvedAtMillis = null,
            ),
        )
    }

    private companion object {
        val HostType = Regex("[A-Z][A-Z0-9_]{0,31}")
        val Sha256 = Regex("[0-9a-f]{64}")
        const val DefaultClaimBatchSize = 4
        const val DefaultWindowItemLimit = 32
        const val MaximumWindowItemLimit = 256
        const val DefaultItemLeaseMillis = 5 * 60 * 1_000L
        const val BaseRetryDelayMillis = 5_000L
        const val MaximumRetryDelayMillis = 5 * 60 * 1_000L
        val ContinueExecution = IndexExecutionControl { true }
    }
}
