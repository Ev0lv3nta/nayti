package app.nayti.indexer

import app.nayti.ml.runtime.ocr.OcrPoint
import app.nayti.ml.runtime.ocr.OcrQuadrilateral
import app.nayti.ml.runtime.ocr.RecognizedOcrRegion
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPublicationBuilderTest {
    @Test
    fun buildsVersionedTextAndNormalizedGeometry() {
        val draft =
            OcrPublicationBuilder().build(
                claim(),
                sourceWidth = 4_000,
                sourceHeight = 3_000,
                decodedWidth = 1_000,
                decodedHeight = 750,
                recognized =
                    listOf(
                        region("  Счёт  ABC-123  ", 0.9f, 100f, 75f, 900f, 150f),
                        region("шум", 0.24f, 0f, 0f, 100f, 100f),
                    ),
            )

        assertEquals("Счёт ABC-123", draft.document.displayText)
        assertEquals("счет abc 123", draft.document.canonicalText)
        assertTrue(draft.document.identifierText.contains("abc123"))
        assertEquals(1, draft.regions.size)
        val region = draft.regions.single()
        assertEquals(900_000, region.confidenceMicros)
        assertEquals(100_000, region.x0Micros)
        assertEquals(100_000, region.y0Micros)
        assertEquals(900_000, region.x2Micros)
        assertEquals(200_000, region.y2Micros)
    }

    @Test
    fun emptyRecognitionProducesSearchableEmptySuccess() {
        val draft =
            OcrPublicationBuilder().build(
                claim(),
                sourceWidth = 10,
                sourceHeight = 10,
                decodedWidth = 10,
                decodedHeight = 10,
                recognized = emptyList(),
            )

        assertTrue(draft.regions.isEmpty())
        assertEquals("", draft.document.rawText)
        assertEquals("", draft.document.canonicalText)
    }

    private fun claim() =
        IndexClaimContext(
            work =
                IndexChannelWorkEntity(
                    assetId = 7,
                    channel = IndexChannel.OCR,
                    state = IndexWorkState.RUNNING,
                    sourceFingerprint = Fingerprint,
                    accessRevision = 4,
                    pipelineVersion = "ocr-v1",
                    componentHash = ComponentHash,
                    attempt = 1,
                    leaseToken = "lease",
                    leaseExpiresAtMillis = 1_000,
                    executionWindowId = "window",
                    publicationToken = null,
                    stagedArtifactPath = null,
                    stagedArtifactLength = null,
                    stagedArtifactSha256 = null,
                    nextEligibleAtMillis = null,
                    errorCode = null,
                    updatedAtMillis = 1,
                ),
            publicationToken = "publication",
        )

    private fun region(
        text: String,
        confidence: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = RecognizedOcrRegion(
        quadrilateral =
            OcrQuadrilateral(
                OcrPoint(left, top),
                OcrPoint(right, top),
                OcrPoint(right, bottom),
                OcrPoint(left, bottom),
            ),
        rawText = text,
        confidence = confidence,
    )

    private companion object {
        const val Fingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ComponentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
