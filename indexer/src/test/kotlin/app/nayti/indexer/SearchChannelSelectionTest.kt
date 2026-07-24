package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchChannelSelectionTest {
    @Test
    fun allSevenNonEmptyCombinationsAreValid() {
        val combinations =
            listOf(
                SearchChannelSelection(true, false, false),
                SearchChannelSelection(false, true, false),
                SearchChannelSelection(false, false, true),
                SearchChannelSelection(true, true, false),
                SearchChannelSelection(true, false, true),
                SearchChannelSelection(false, true, true),
                SearchChannelSelection(true, true, true),
            )

        assertEquals(7, combinations.distinct().size)
        assertTrue(combinations.all(SearchChannelSelection::hasAny))
        assertFalse(combinations.single { it.visual && !it.usesText }.usesText)
    }

    @Test
    fun lastEnabledChannelCannotBeDeselected() {
        val visualOnly =
            SearchChannelSelection.All
                .set(SearchChannel.OCR_LITERAL, false)
                .set(SearchChannel.OCR_SEMANTIC, false)

        assertEquals(visualOnly, visualOnly.set(SearchChannel.VISUAL, false))
        assertTrue(visualOnly.visual)
        assertFalse(visualOnly.usesText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySelectionCannotBeConstructed() {
        SearchChannelSelection(ocrLiteral = false, ocrSemantic = false, visual = false)
    }
}
