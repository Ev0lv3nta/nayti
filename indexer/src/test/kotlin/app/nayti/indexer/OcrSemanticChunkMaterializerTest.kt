package app.nayti.indexer

import app.nayti.storage.OcrDocumentEntity
import app.nayti.storage.OcrRegionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSemanticChunkMaterializerTest {
    private val materializer =
        OcrSemanticChunkMaterializer(
            OcrSemanticChunkPlanner(SemanticTokenCounter { text -> Regex("\\S+").findAll(text).count() }),
        )

    @Test
    fun bindsPlannerOutputToExactOcrPublication() {
        val materialized = materializer.materialize(document(), listOf(region(0), region(1)), 200)

        assertEquals(SourceFingerprint, materialized.chunkSet.sourceFingerprint)
        assertEquals(PublicationToken, materialized.chunkSet.ocrPublicationToken)
        assertEquals(OcrSemanticChunkPlanner.ChunkingVersion, materialized.chunkSet.chunkingVersion)
        assertEquals(2, materialized.chunkSet.chunkCount)
        assertEquals(listOf(0, 1), materialized.chunks.map { it.ordinal })
        assertTrue(materialized.lines.all { it.assetId == AssetId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRegionsFromAnotherAsset() {
        materializer.materialize(document(), listOf(region(0).copy(assetId = 8), region(1)), 200)
    }

    private fun document() =
        OcrDocumentEntity(
            assetId = AssetId,
            sourceFingerprint = SourceFingerprint,
            accessRevision = 3,
            pipelineVersion = "ocr-v1",
            componentHash = ComponentHash,
            publicationToken = PublicationToken,
            publicationEpoch = 4,
            sourceWidth = 100,
            sourceHeight = 100,
            rawText = "Quarterly product report\nRevenue increased",
            displayText = "Quarterly product report\nRevenue increased",
            canonicalText = "quarterly product report revenue increased",
            stemText = "",
            identifierText = "",
            hasRecognizedText = true,
            regionCount = 2,
            normalizerVersion = "normalizer-v1",
            stemmerVersion = "stemmer-v1",
            identifierVersion = "identifier-v1",
            publishedAtMillis = 100,
        )

    private fun region(ordinal: Int) =
        OcrRegionEntity(
            publicationEpoch = 1,
            assetId = AssetId,
            ordinal = ordinal,
            rawText = if (ordinal == 0) "Quarterly product report" else "Revenue increased",
            displayText = if (ordinal == 0) "Quarterly product report" else "Revenue increased",
            canonicalText = "",
            confidenceMicros = 900_000,
            x0Micros = 0,
            y0Micros = 0,
            x1Micros = 1,
            y1Micros = 0,
            x2Micros = 1,
            y2Micros = 1,
            x3Micros = 0,
            y3Micros = 1,
        )

    private companion object {
        const val AssetId = 7L
        const val PublicationToken = "ocr-publication-a"
        const val SourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ComponentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
