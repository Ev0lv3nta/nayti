package app.nayti.indexer

import app.nayti.storage.OcrDocumentEntity
import app.nayti.storage.OcrRegionEntity
import app.nayti.storage.OcrSemanticChunkCodec
import app.nayti.storage.OcrSemanticChunkMaterialization
import app.nayti.storage.OcrSemanticChunkPayload
import app.nayti.storage.OcrSemanticChunkSetDraft

class OcrSemanticChunkMaterializer(
    private val planner: OcrSemanticChunkPlanner,
) {
    fun materialize(
        document: OcrDocumentEntity,
        regions: List<OcrRegionEntity>,
        createdAtMillis: Long,
    ): OcrSemanticChunkMaterialization {
        require(document.assetId > 0)
        require(document.regionCount == regions.size)
        require(regions.all { it.assetId == document.assetId })
        require(regions.map(OcrRegionEntity::ordinal) == regions.indices.toList())

        val planned =
            planner.plan(
                regions.map { region ->
                    SemanticOcrLine(
                        ordinal = region.ordinal,
                        displayText = region.displayText,
                        confidenceMicros = region.confidenceMicros,
                    )
                },
            )
        return OcrSemanticChunkCodec.materialize(
            OcrSemanticChunkSetDraft(
                assetId = document.assetId,
                sourceFingerprint = document.sourceFingerprint,
                ocrPublicationToken = document.publicationToken,
                chunkingVersion = OcrSemanticChunkPlanner.ChunkingVersion,
                chunks =
                    planned.map { chunk ->
                        OcrSemanticChunkPayload(
                            ordinal = chunk.ordinal,
                            kind = chunk.kind.name,
                            displayText = chunk.displayText,
                            contentTokenCount = chunk.contentTokenCount,
                            lineOrdinals = chunk.lineOrdinals,
                            meanConfidenceMicros = chunk.meanConfidenceMicros,
                            reliableAlphabeticWordCount = chunk.reliableAlphabeticWordCount,
                        )
                    },
            ),
            createdAtMillis = createdAtMillis,
        )
    }
}
