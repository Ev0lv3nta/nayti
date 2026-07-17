package app.nayti.search.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `normalizes surrounding whitespace`() {
        assertEquals("квитанция", SearchQuery.parse("  квитанция  ")?.value)
    }

    @Test
    fun `rejects blank query`() {
        assertNull(SearchQuery.parse("  "))
    }
}
