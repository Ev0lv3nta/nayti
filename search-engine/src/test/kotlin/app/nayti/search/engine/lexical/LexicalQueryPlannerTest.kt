package app.nayti.search.engine.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalQueryPlannerTest {
    private val planner = LexicalQueryPlanner()

    @Test
    fun emptyAndQuotedQueriesHaveExplicitIntent() {
        assertEquals(LexicalIntent.EMPTY, planner.plan("   ").intent)
        assertNull(planner.plan("   ").ftsMatch)

        val quoted = planner.plan("\"Счёт за кофе\"")
        assertEquals(LexicalIntent.QUOTED_PHRASE, quoted.intent)
        assertEquals(listOf("счет за кофе"), quoted.quotedPhrases)
        assertEquals("canonical : \"счет за кофе\"", quoted.ftsMatch)
        assertNull(quoted.trigramMatch)
    }

    @Test
    fun identifierAndPersonNameUseDifferentPlans() {
        val identifier = planner.plan("АВС-123")
        assertEquals(LexicalIntent.IDENTIFIER, identifier.intent)
        assertTrue(identifier.ftsMatch.orEmpty().contains("identifiers :"))
        assertTrue(identifier.identifierForms.any { form -> form.contains("abc123") })

        val person = planner.plan("Иван Иванов")
        assertEquals(LexicalIntent.PERSON_NAME, person.intent)
        assertTrue(person.ftsMatch.orEmpty().contains("stems :"))
        assertTrue(person.trigramMatch.orEmpty().isNotBlank())
    }

    @Test
    fun userSyntaxCannotEscapeQuotedFtsLiterals() {
        val plan = planner.plan("кофе\" OR * identifiers:secret")
        val match = plan.ftsMatch.orEmpty()

        assertFalse(match.contains('*'))
        assertFalse(match.contains("identifiers:secret"))
        assertTrue(match.contains("\"кофе\""))
        assertTrue(match.contains("\"or\""))
        assertTrue(match.contains("\"identifiers\""))
        assertTrue(match.contains("\"secret\""))
    }

    @Test
    fun fuzzyCandidateExpressionIsBoundedAndContainsOnlyQuotedTrigrams() {
        val plan = planner.plan("рестаран")
        val grams = checkNotNull(plan.trigramMatch).split(" OR ")

        assertTrue(grams.size <= LexicalQueryPlanner.MaximumTrigrams)
        assertTrue(grams.all { gram -> gram.startsWith('"') && gram.endsWith('"') && gram.length == 5 })
        assertTrue(grams.contains("\"рес\""))
    }
}
