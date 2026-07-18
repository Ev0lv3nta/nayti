package app.nayti.indexer

import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.IndexChannel

enum class ActivationChannelAction {
    INHERIT,
    REBUILD_SHADOW,
}

enum class ActivationInvalidationReason {
    EXACT_COMPONENT_MATCH,
    MISSING_ACTIVE_COMPONENT,
    PIPELINE_CHANGED,
    COMPONENT_CHANGED,
    EMBEDDING_SPACE_CHANGED,
    OCR_DEPENDENCY_CHANGED,
}

data class ActivationChannelPlan(
    val channel: String,
    val action: ActivationChannelAction,
    val reason: ActivationInvalidationReason,
)

/** Produces a deterministic minimal rebuild set from exact per-channel provenance. */
object ActivationDependencyPlanner {
    fun plan(
        active: List<ActivationSnapshotChannelEntity>,
        candidate: List<ActivationSnapshotChannelEntity>,
    ): List<ActivationChannelPlan> {
        require(candidate.isNotEmpty())
        require(candidate.map { it.channel }.distinct().size == candidate.size)
        require(candidate.all { it.channel in IndexChannel.all })
        val activeByChannel = active.associateBy(ActivationSnapshotChannelEntity::channel)
        val candidateByChannel = candidate.associateBy(ActivationSnapshotChannelEntity::channel)
        val ocrChanged = candidateByChannel[IndexChannel.OCR]?.let { next ->
            exactContract(activeByChannel[IndexChannel.OCR], next) != null
        } ?: false
        return candidate.sortedBy(ActivationSnapshotChannelEntity::channel).map { next ->
            val previous = activeByChannel[next.channel]
            val directReason = exactContract(previous, next)
            val reason =
                if (next.channel == IndexChannel.OCR_SEMANTIC && ocrChanged) {
                    ActivationInvalidationReason.OCR_DEPENDENCY_CHANGED
                } else {
                    directReason ?: ActivationInvalidationReason.EXACT_COMPONENT_MATCH
                }
            ActivationChannelPlan(
                channel = next.channel,
                action =
                    if (reason == ActivationInvalidationReason.EXACT_COMPONENT_MATCH) {
                        ActivationChannelAction.INHERIT
                    } else {
                        ActivationChannelAction.REBUILD_SHADOW
                    },
                reason = reason,
            )
        }
    }

    private fun exactContract(
        previous: ActivationSnapshotChannelEntity?,
        next: ActivationSnapshotChannelEntity,
    ): ActivationInvalidationReason? =
        when {
            previous == null -> ActivationInvalidationReason.MISSING_ACTIVE_COMPONENT
            previous.pipelineVersion != next.pipelineVersion -> ActivationInvalidationReason.PIPELINE_CHANGED
            previous.componentHash != next.componentHash -> ActivationInvalidationReason.COMPONENT_CHANGED
            previous.embeddingSpaceHash != next.embeddingSpaceHash ->
                ActivationInvalidationReason.EMBEDDING_SPACE_CHANGED
            else -> null
        }
}
