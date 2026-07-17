package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OcrSemanticChunkPlannerTest {
    private val planner = OcrSemanticChunkPlanner(SemanticTokenCounter(::whitespaceTokens))

    @Test
    fun preservesLineStructureConfidenceAndStableHeader() {
        val chunks =
            planner.plan(
                listOf(
                    line(4, "Quarterly product report", 900_000),
                    line(7, "Revenue rose in Europe", 700_000),
                    line(9, "internal 42", 300_000),
                ),
            )

        assertEquals(2, chunks.size)
        val header = chunks[0]
        assertEquals(OcrSemanticChunkKind.HEADER, header.kind)
        assertEquals("Quarterly product report\nRevenue rose in Europe\ninternal 42", header.displayText)
        assertEquals(listOf(4, 7, 9), header.lineOrdinals)
        assertEquals(4, header.firstLineOrdinal)
        assertEquals(9, header.lastLineOrdinal)
        assertEquals(633_333, header.meanConfidenceMicros)
        assertEquals(7, header.reliableAlphabeticWordCount)
        assertEquals(OcrSemanticChunkPlanner.ChunkingVersion, header.chunkingVersion)
        assertEquals(OcrSemanticChunkKind.BODY, chunks[1].kind)
        assertEquals(0, header.ordinal)
        assertEquals(1, chunks[1].ordinal)
    }

    @Test
    fun splitsLongLineAtExactBudgetWithTwentyFourTokenOverlap() {
        val words = (1..120).joinToString(" ") { index -> "word$index" }
        val chunks = planner.plan(listOf(line(0, words)))

        assertEquals(3, chunks.size)
        assertEquals(OcrSemanticChunkKind.HEADER, chunks[0].kind)
        assertEquals(96, chunks[0].contentTokenCount)
        assertEquals(96, chunks[1].contentTokenCount)
        assertTrue(chunks[1].displayText.startsWith("word1 "))
        assertEquals(48, chunks[2].contentTokenCount)
        assertTrue(chunks[2].displayText.startsWith("word73 "))
        assertTrue(chunks[2].displayText.endsWith(" word120"))
    }

    @Test
    fun capsOutputAndRemainsDeterministic() {
        val lines =
            (0 until 40).map { ordinal ->
                line(ordinal, (0 until 96).joinToString(" ") { index -> "term${ordinal}_$index" })
            }

        val first = planner.plan(lines)
        val second = planner.plan(lines)

        assertEquals(OcrSemanticChunkPlanner.MaximumChunks, first.size)
        assertEquals(first, second)
        assertEquals((0 until OcrSemanticChunkPlanner.MaximumChunks).toList(), first.map { it.ordinal })
    }

    @Test
    fun excludesSemanticGarbageWithoutMutatingLiteralInput() {
        val lines =
            listOf(
                line(0, "a 1 !", 950_000),
                line(1, "readable invoice words", 449_999),
            )
        val original = lines.toList()

        assertTrue(planner.plan(lines).isEmpty())
        assertEquals(original, lines)
    }

    @Test
    fun skipsOnePathologicalWordButKeepsBoundedUsefulText() {
        val pathological = "x".repeat(200)
        val characterCounter = SemanticTokenCounter(String::length)
        val chunks = OcrSemanticChunkPlanner(characterCounter).plan(
            listOf(line(0, "$pathological useful report text")),
        )

        assertEquals(2, chunks.size)
        assertEquals("useful report text", chunks[0].displayText)
        assertEquals(18, chunks[0].contentTokenCount)
    }

    @Test
    fun rejectsInvalidInputAndBrokenTokenizer() {
        assertFails { planner.plan(listOf(line(2, "valid words here"), line(1, "out of order"))) }
        assertFails { planner.plan(listOf(line(0, "valid words here", 1_000_001))) }
        assertFails {
            OcrSemanticChunkPlanner(SemanticTokenCounter { -1 })
                .plan(listOf(line(0, "valid words here")))
        }
    }

    private fun line(
        ordinal: Int,
        text: String,
        confidenceMicros: Int = 900_000,
    ) = SemanticOcrLine(ordinal, text, confidenceMicros)

    private fun whitespaceTokens(text: String): Int = Regex("\\S+").findAll(text).count()

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected invariant rejection.
        }
    }
}
