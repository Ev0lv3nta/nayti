package app.nayti.indexer

import app.nayti.search.engine.NativeVectorIndex
import app.nayti.storage.ActivationCandidateChannelAction
import app.nayti.storage.ActivationCandidateChannelEntity
import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelCoverage
import app.nayti.storage.VectorManifestEntity
import app.nayti.platform.media.BoundedMediaDecoder
import java.io.File

fun interface CandidateBuildControl {
    fun shouldContinue(): Boolean
}

/** Builds every invalidated channel against one candidate root and catches up catalog deltas before cutover. */
class ProductionCandidateShadowBuilder(
    private val storage: CatalogStorage,
    private val packResolver: InstalledOcrPackResolver,
    private val decoder: BoundedMediaDecoder,
    private val vectorRoot: File,
    private val activation: CandidateActivationGateway,
    private val neuralLane: NeuralExecutionLane,
    private val control: CandidateBuildControl = CandidateBuildControl { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : CandidateShadowBuilder {
    override suspend fun prepare(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
    ): PreparedCandidateSnapshot {
        validatePlan(candidate, plan)
        var reconciled = candidate
        repeat(MaximumCatalogReconciliations) {
            continueOrDefer()
            buildCurrentCatalog(reconciled, plan)
            val currentWatermark = storage.catalogDao.watermark()?.catalogRevision ?: 0
            if (currentWatermark == reconciled.capturedCatalogWatermark) {
                return assemble(reconciled, plan)
            }
            check(currentWatermark > reconciled.capturedCatalogWatermark)
            reconciled = activation.reconcileCatalogWatermark(
                candidateId = reconciled.candidateId,
                expectedWatermark = reconciled.capturedCatalogWatermark,
                nextWatermark = currentWatermark,
            )
        }
        throw CandidatePreparationDeferredException("Catalog did not stabilize during candidate preparation")
    }

    private suspend fun buildCurrentCatalog(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
    ) {
        val rebuilding = plan.filter { it.action == ActivationCandidateChannelAction.REBUILD_SHADOW }
        if (rebuilding.isEmpty()) return
        val operation =
            IndexExecutionCoordinator(
                indexState = storage.indexStateDao,
                catalog = storage.catalogDao,
                executors = emptyMap(),
            ).run {
                recoverExpiredExecution()
                planOperation(
                    IndexOperationRequest(
                        operationId = operationId(candidate),
                        profileId = CandidateProfile,
                        targetPackId = candidate.packId,
                        targetPackVersion = candidate.packVersion,
                        channels = rebuilding.sortedBy(::priority).map { target ->
                            IndexChannelContract(
                                channel = target.channel,
                                priority = priority(target),
                                pipelineVersion = target.pipelineVersion,
                                componentHash = target.componentHash,
                            )
                        },
                        autoResume = true,
                    ),
                )
            }
        rebuilding.sortedBy(::priority).forEach { target ->
            while (true) {
                continueOrDefer()
                val coverage = coverage(candidate, target)
                if (coverage.outstandingAssetCount == 0L) {
                    check(coverage.permanentGapCount == 0L) {
                        "Candidate channel ${target.channel} has permanent gaps"
                    }
                    break
                }
                val report = runWindow(candidate, operation.operationId, target)
                if (report.claimed == 0) {
                    throw CandidatePreparationDeferredException(
                        "Candidate channel ${target.channel} is waiting for retry eligibility",
                    )
                }
            }
        }
    }

    private suspend fun runWindow(
        candidate: ActivationCandidateEntity,
        operationId: String,
        target: ActivationCandidateChannelEntity,
    ): IndexExecutionReport =
        when (target.channel) {
            IndexChannel.OCR ->
                neuralLane.withPermit {
                    OcrExecutionSession.open(
                        packId = candidate.packId,
                        packVersion = candidate.packVersion,
                        resolver = packResolver,
                        ocr = storage.ocrDao,
                        decoder = decoder,
                    ).use { session -> executeWindow(operationId, target.channel, session.executor) }
                }
            IndexChannel.OCR_SEMANTIC ->
                neuralLane.withPermit {
                    OcrSemanticExecutionSession.open(
                        packId = candidate.packId,
                        packVersion = candidate.packVersion,
                        resolver = packResolver,
                        indexState = storage.indexStateDao,
                        semantic = storage.ocrSemanticDao,
                        vectors = storage.vectorIndexDao,
                        vectorRoot = vectorRoot,
                        candidateSnapshotId = candidate.snapshotId,
                    ).use { session -> executeWindow(operationId, target.channel, session.executor) }
                }
            IndexChannel.VISUAL ->
                neuralLane.withPermit {
                    VisualExecutionSession.open(
                        packId = candidate.packId,
                        packVersion = candidate.packVersion,
                        resolver = packResolver,
                        indexState = storage.indexStateDao,
                        semantic = storage.ocrSemanticDao,
                        hashes = storage.perceptualHashDao,
                        vectors = storage.vectorIndexDao,
                        decoder = decoder,
                        vectorRoot = vectorRoot,
                        candidateSnapshotId = candidate.snapshotId,
                    ).use { session -> executeWindow(operationId, target.channel, session.executor) }
                }
            else -> error("Candidate cannot rebuild ${target.channel}")
        }

    private suspend fun executeWindow(
        operationId: String,
        channel: String,
        executor: IndexChannelExecutor,
    ): IndexExecutionReport {
        val coordinator = IndexExecutionCoordinator(
            indexState = storage.indexStateDao,
            catalog = storage.catalogDao,
            executors = mapOf(channel to executor),
        )
        val window = coordinator.startExecutionWindow(
            operationId = operationId,
            hostType = OcrExecutionHost.UserForegroundService,
            durationMillis = ExecutionWindowMillis,
        )
        return coordinator.runWindow(
            windowId = window.windowId,
            itemLimit = WindowItemLimit,
            control = IndexExecutionControl(control::shouldContinue),
            channelsToRun = setOf(channel),
        )
    }

    private suspend fun coverage(
        candidate: ActivationCandidateEntity,
        target: ActivationCandidateChannelEntity,
    ): IndexChannelCoverage {
        val scope = storage.catalogDao.currentIndexingScope()
        return storage.indexStateDao.channelCoverage(
            channel = target.channel,
            accessRevision = candidate.capturedAccessRevision,
            pipelineVersion = target.pipelineVersion,
            componentHash = target.componentHash,
            takenFromMillis = scope.takenFromMillis,
        )
    }

    private suspend fun assemble(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
    ): PreparedCandidateSnapshot {
        val parentSnapshotId = checkNotNull(candidate.parentSnapshotId)
        val parent = checkNotNull(storage.vectorIndexDao.snapshot(parentSnapshotId))
        val parentChannels = storage.vectorIndexDao.snapshotChannels(parent.snapshotId).associateBy { it.channel }
        val channels = plan.map { target ->
            if (target.action == ActivationCandidateChannelAction.INHERIT) {
                checkNotNull(parentChannels[target.channel]).copy(
                    snapshotId = candidate.snapshotId,
                    inheritedFromSnapshotId = parent.snapshotId,
                )
            } else {
                rebuiltChannel(candidate, target)
            }
        }.sortedBy(ActivationSnapshotChannelEntity::channel)
        val byChannel = channels.associateBy(ActivationSnapshotChannelEntity::channel)
        val lexicalEpoch =
            if (plan.single { it.channel == IndexChannel.OCR }.action == ActivationCandidateChannelAction.INHERIT) {
                parent.lexicalPublicationEpoch
            } else {
                storage.ocrSemanticDao.maximumOcrPublicationEpoch()
            }
        val snapshot = ActivationSnapshotEntity(
            snapshotId = candidate.snapshotId,
            parentSnapshotId = candidate.parentSnapshotId,
            packId = candidate.packId,
            packVersion = candidate.packVersion,
            packManifestSha256 = candidate.packManifestSha256,
            engineContractVersion = NativeVectorIndex.contractVersion(),
            rankingConfigVersion = parent.rankingConfigVersion,
            lexicalPublicationEpoch = lexicalEpoch,
            pHashPublicationEpoch = parent.pHashPublicationEpoch,
            semanticManifestRevision = byChannel[IndexChannel.OCR_SEMANTIC]?.manifestRevision,
            visualManifestRevision = byChannel[IndexChannel.VISUAL]?.manifestRevision,
            catalogWatermark = candidate.capturedCatalogWatermark,
            createdAtMillis = clock(),
            capturedAccessRevision = candidate.capturedAccessRevision,
        )
        return PreparedCandidateSnapshot(snapshot, channels)
    }

    private suspend fun rebuiltChannel(
        candidate: ActivationCandidateEntity,
        target: ActivationCandidateChannelEntity,
    ): ActivationSnapshotChannelEntity {
        if (target.channel == IndexChannel.OCR) {
            return ActivationSnapshotChannelEntity(
                snapshotId = candidate.snapshotId,
                channel = target.channel,
                pipelineVersion = target.pipelineVersion,
                componentHash = target.componentHash,
                embeddingSpaceHash = null,
                generationId = null,
                manifestRevision = null,
                inheritedFromSnapshotId = null,
            )
        }
        val publication = checkNotNull(
            storage.vectorIndexDao.latestCompletedPublication(candidate.snapshotId, target.channel),
        ) { "Candidate vector channel ${target.channel} has no shadow publication" }
        val manifest = checkNotNull(storage.vectorIndexDao.manifest(publication.manifestRevision))
        val generation = checkNotNull(storage.vectorIndexDao.generation(manifest.generationId))
        validateVectorContract(target, manifest, generation.componentHash, generation.embeddingSpaceHash)
        return ActivationSnapshotChannelEntity(
            snapshotId = candidate.snapshotId,
            channel = target.channel,
            pipelineVersion = target.pipelineVersion,
            componentHash = target.componentHash,
            embeddingSpaceHash = target.embeddingSpaceHash,
            generationId = generation.generationId,
            manifestRevision = manifest.revision,
            inheritedFromSnapshotId = null,
        )
    }

    private fun validateVectorContract(
        target: ActivationCandidateChannelEntity,
        manifest: VectorManifestEntity,
        componentHash: String,
        embeddingSpaceHash: String,
    ) {
        check(manifest.channel == target.channel)
        check(componentHash == target.componentHash)
        check(embeddingSpaceHash == target.embeddingSpaceHash)
    }

    private fun validatePlan(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
    ) {
        check(plan.isNotEmpty() && plan.all { it.candidateId == candidate.candidateId })
        check(plan.map { it.channel }.distinct().size == plan.size)
        check(plan.any { it.channel == IndexChannel.OCR })
        check(plan.any { it.channel in setOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL) })
    }

    private fun operationId(candidate: ActivationCandidateEntity): String =
        "candidate-${candidate.candidateId.take(48)}-${candidate.capturedCatalogWatermark}"

    private fun priority(target: ActivationCandidateChannelEntity): Int = when (target.channel) {
        IndexChannel.OCR -> 0
        IndexChannel.OCR_SEMANTIC -> 1
        IndexChannel.VISUAL -> 2
        else -> 3
    }

    private fun continueOrDefer() {
        if (!control.shouldContinue()) {
            throw CandidatePreparationDeferredException("Candidate preparation was stopped by its execution host")
        }
    }

    private companion object {
        const val CandidateProfile = "candidate-balanced-v1"
        const val WindowItemLimit = 256
        const val ExecutionWindowMillis = 10L * 60 * 1_000
        const val MaximumCatalogReconciliations = 8
    }
}
