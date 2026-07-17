package app.nayti.search.engine.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFuzzyMatcherTest {
    @Test
    fun distanceSupportsInsertDeleteSubstituteAndAdjacentTranspose() {
        assertEquals(0, BoundedFuzzyMatcher.distance("договор", "договор", 2))
        assertEquals(1, BoundedFuzzyMatcher.distance("договор", "договр", 2))
        assertEquals(1, BoundedFuzzyMatcher.distance("ресторан", "рестаран", 2))
        assertEquals(1, BoundedFuzzyMatcher.distance("кофе", "коеф", 2))
        assertEquals(3, BoundedFuzzyMatcher.distance("кофе", "мореход", 2))
    }

    @Test
    fun bestMatchesReturnsOnlyBoundedEvidencePerQueryTerm() {
        val matches = BoundedFuzzyMatcher.bestMatches(listOf("рестаран", "невскй"), "ресторан на невском", 2)

        assertEquals(2, matches.size)
        assertEquals("ресторан", matches[0].documentToken)
        assertEquals(1, matches[0].distance)
        assertEquals("невском", matches[1].documentToken)
        assertTrue(matches[1].distance <= 2)
    }
}
