package app.nayti.indexer

import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.IndexChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivationDependencyPlannerTest {
    @Test
    fun unrelatedPackChangeInheritsEveryExactChannel() {
        val active = contracts("active")
        val candidate = active.map { it.copy(snapshotId = "candidate") }

        val plan = ActivationDependencyPlanner.plan(active, candidate)

        assertEquals(IndexChannel.all.size, plan.size)
        assertEquals(setOf(ActivationChannelAction.INHERIT), plan.map { it.action }.toSet())
    }

    @Test
    fun ocrChangeInvalidatesOcrAndDependentSemanticButNotVisualOrPhash() {
        val active = contracts("active")
        val candidate =
            active.map { component ->
                component.copy(
                    snapshotId = "candidate",
                    componentHash =
                        if (component.channel == IndexChannel.OCR) "f".repeat(64) else component.componentHash,
                )
            }

        val byChannel = ActivationDependencyPlanner.plan(active, candidate).associateBy { it.channel }

        assertEquals(ActivationInvalidationReason.COMPONENT_CHANGED, byChannel.getValue(IndexChannel.OCR).reason)
        assertEquals(
            ActivationInvalidationReason.OCR_DEPENDENCY_CHANGED,
            byChannel.getValue(IndexChannel.OCR_SEMANTIC).reason,
        )
        assertEquals(ActivationChannelAction.INHERIT, byChannel.getValue(IndexChannel.VISUAL).action)
        assertEquals(ActivationChannelAction.INHERIT, byChannel.getValue(IndexChannel.PHASH).action)
    }

    @Test
    fun visualEmbeddingChangeRebuildsOnlyVisualGeneration() {
        val active = contracts("active")
        val candidate =
            active.map { component ->
                component.copy(
                    snapshotId = "candidate",
                    embeddingSpaceHash =
                        if (component.channel == IndexChannel.VISUAL) "e".repeat(64) else component.embeddingSpaceHash,
                )
            }

        val changed = ActivationDependencyPlanner.plan(active, candidate).filter {
            it.action == ActivationChannelAction.REBUILD_SHADOW
        }

        assertEquals(listOf(IndexChannel.VISUAL), changed.map { it.channel })
        assertEquals(ActivationInvalidationReason.EMBEDDING_SPACE_CHANGED, changed.single().reason)
    }

    private fun contracts(snapshotId: String) =
        IndexChannel.all.map { channel ->
            val vector = channel in setOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL)
            ActivationSnapshotChannelEntity(
                snapshotId = snapshotId,
                channel = channel,
                pipelineVersion = "${channel.lowercase()}-v1",
                componentHash = channel.first().code.toString(16).padStart(64, '0'),
                embeddingSpaceHash = if (vector) channel.last().code.toString(16).padStart(64, '0') else null,
                generationId = if (vector) "generation-$channel" else null,
                manifestRevision = if (vector) "manifest-$channel" else null,
                inheritedFromSnapshotId = null,
            )
        }
}
