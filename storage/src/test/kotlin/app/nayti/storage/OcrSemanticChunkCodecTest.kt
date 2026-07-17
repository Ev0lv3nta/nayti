package app.nayti.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OcrSemanticChunkCodecTest {
    @Test
    fun materializationIsDeterministicAndEveryChunkHasStableIdentity() {
        val first = OcrSemanticChunkCodec.materialize(draft(), 100)
        val second = OcrSemanticChunkCodec.materialize(draft(), 100)

        assertEquals(first, second)
        assertEquals(2, first.chunkSet.chunkCount)
        assertEquals(first.chunkSet.chunkSetId, first.chunkSet.payloadSha256)
        assertEquals(listOf(0, 1), first.chunks.map(OcrSemanticChunkEntity::ordinal))
        assertEquals(listOf(0, 1, 2), first.lines.map(OcrSemanticChunkLineEntity::lineOrdinal))
        assertTrue(first.chunks.all { it.chunkSetId == first.chunkSet.chunkSetId })
        assertTrue(first.chunks.map(OcrSemanticChunkEntity::chunkId).distinct().size == 2)
    }

    @Test
    fun exactContentAndStructureAffectIdentityButTimestampDoesNot() {
        val baseline = OcrSemanticChunkCodec.materialize(draft(), 100)
        val later = OcrSemanticChunkCodec.materialize(draft(), 200)
        val changed =
            OcrSemanticChunkCodec.materialize(
                draft().copy(
                    chunks = draft().chunks.toMutableList().also { chunks ->
                        chunks[1] = chunks[1].copy(lineOrdinals = listOf(1))
                    },
                ),
                100,
            )

        assertEquals(baseline.chunkSet.chunkSetId, later.chunkSet.chunkSetId)
        assertNotEquals(baseline.chunkSet, later.chunkSet)
        assertNotEquals(baseline.chunkSet.chunkSetId, changed.chunkSet.chunkSetId)
    }

    @Test
    fun emptySemanticResultIsAFirstClassDurableSet() {
        val materialized = OcrSemanticChunkCodec.materialize(draft().copy(chunks = emptyList()), 100)

        assertEquals(0, materialized.chunkSet.chunkCount)
        assertTrue(materialized.chunks.isEmpty())
        assertTrue(materialized.lines.isEmpty())
    }

    @Test
    fun rejectsUnboundedOrAmbiguousPayload() {
        assertFails { OcrSemanticChunkCodec.materialize(draft().copy(sourceFingerprint = "not-a-hash"), 100) }
        assertFails {
            OcrSemanticChunkCodec.materialize(
                draft().copy(chunks = listOf(draft().chunks.first().copy(ordinal = 1))),
                100,
            )
        }
        assertFails {
            OcrSemanticChunkCodec.materialize(
                draft().copy(chunks = listOf(draft().chunks.first().copy(lineOrdinals = listOf(1, 1)))),
                100,
            )
        }
    }

    private fun draft() =
        OcrSemanticChunkSetDraft(
            assetId = 7,
            sourceFingerprint = SourceFingerprint,
            ocrPublicationToken = "ocr-publication-a",
            chunkingVersion = "ocr-semantic-chunks-v1",
            chunks =
                listOf(
                    OcrSemanticChunkPayload(
                        ordinal = 0,
                        kind = "HEADER",
                        displayText = "Quarterly product report",
                        contentTokenCount = 3,
                        lineOrdinals = listOf(0),
                        meanConfidenceMicros = 900_000,
                        reliableAlphabeticWordCount = 3,
                    ),
                    OcrSemanticChunkPayload(
                        ordinal = 1,
                        kind = "BODY",
                        displayText = "Revenue increased in Europe",
                        contentTokenCount = 4,
                        lineOrdinals = listOf(1, 2),
                        meanConfidenceMicros = 800_000,
                        reliableAlphabeticWordCount = 4,
                    ),
                ),
        )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected invariant rejection.
        }
    }

    private companion object {
        const val SourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
