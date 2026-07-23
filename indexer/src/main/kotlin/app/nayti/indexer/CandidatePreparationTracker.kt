package app.nayti.indexer

import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import app.nayti.storage.ModelPackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes durable shadow-build coverage without exposing candidate documents to active search.
 *
 * Candidate work uses the same channel ledger as ordinary indexing, but its component contract is
 * different from the active pack. Reading that contract explicitly keeps readiness honest while
 * atomic snapshot activation still controls what search can see.
 */
class CandidatePreparationTracker(
    private val storage: CatalogStorage,
) {
    private val mutableState = MutableStateFlow(EmptyState)
    val state: StateFlow<OcrIndexingState> = mutableState.asStateFlow()

    suspend fun publish(
        pack: ModelPackEntity?,
        candidateId: String? = null,
        targetChannels: List<ActivationSnapshotChannelEntity> = emptyList(),
        running: Boolean = false,
        failureCode: String? = null,
    ) {
        try {
            publishState(pack, candidateId, targetChannels, running, failureCode)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Progress reporting is best-effort and must never reject otherwise valid candidate work.
        }
    }

    private suspend fun publishState(
        pack: ModelPackEntity?,
        candidateId: String?,
        targetChannels: List<ActivationSnapshotChannelEntity>,
        running: Boolean,
        failureCode: String?,
    ) {
        if (pack == null || targetChannels.isEmpty()) {
            mutableState.value = EmptyState
            return
        }
        val scope = storage.catalogDao.indexingScopeSummary()
        val access = storage.catalogDao.accessObservation()
        if (access == null || access.accessScope == "None") {
            mutableState.value = EmptyState.copy(scope = scope)
            return
        }
        val candidate =
            if (candidateId == null) {
                null
            } else {
                storage.vectorIndexDao.activationCandidate(candidateId)
            }
        val capabilities = targetChannels.mapNotNull { target ->
            target.channel.toCapability()?.let { capability ->
                val coverage = storage.indexStateDao.channelCoverage(
                    channel = target.channel,
                    accessRevision = access.processAccessRevision,
                    pipelineVersion = target.pipelineVersion,
                    componentHash = target.componentHash,
                    takenFromMillis = scope.takenFromMillis,
                )
                SearchCapabilityCoverage(
                    capability = capability,
                    accessible = coverage.accessibleAssetCount,
                    committed = coverage.committedAssetCount,
                    permanentGaps = coverage.permanentGapCount,
                    outstanding = coverage.outstandingAssetCount,
                )
            }
        }
        mutableState.value = candidatePreparationState(
            candidate = candidate,
            running = running,
            failureCode = failureCode,
            capabilities = capabilities,
            scope = scope,
        )
    }

    private fun String.toCapability(): SearchCapability? = when (this) {
        IndexChannel.OCR -> SearchCapability.TEXT
        IndexChannel.OCR_SEMANTIC -> SearchCapability.MEANING
        IndexChannel.VISUAL -> SearchCapability.VISUAL
        IndexChannel.PHASH -> SearchCapability.DUPLICATES
        else -> null
    }

    private companion object {
        val EmptyState =
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

internal fun candidatePreparationState(
    candidate: ActivationCandidateEntity?,
    running: Boolean,
    failureCode: String?,
    capabilities: List<SearchCapabilityCoverage>,
    scope: IndexingScopeSummary,
): OcrIndexingState {
    val completion =
        capabilities.firstOrNull { it.capability == SearchCapability.VISUAL }
            ?: capabilities.firstOrNull()
            ?: SearchCapabilityCoverage(SearchCapability.TEXT, 0, 0, 0, 0)
    val rejected = candidate?.state == ActivationCandidateState.REJECTED
    return OcrIndexingState(
        status =
            when {
                running -> OcrIndexingStatus.Running
                rejected || failureCode != null -> OcrIndexingStatus.Failed
                completion.outstanding == 0L && capabilities.isNotEmpty() -> OcrIndexingStatus.Ready
                else -> OcrIndexingStatus.Waiting
            },
        accessible = completion.accessible,
        committed = completion.committed,
        permanentGaps = completion.permanentGaps,
        outstanding = completion.outstanding,
        lastSlicePublished = 0,
        errorCode = failureCode ?: candidate?.failureCode,
        operationId = null,
        operationState = candidate?.state,
        hostType = if (running) OcrExecutionHost.UserForegroundService else null,
        capabilities = capabilities,
        scope = scope,
    )
}
