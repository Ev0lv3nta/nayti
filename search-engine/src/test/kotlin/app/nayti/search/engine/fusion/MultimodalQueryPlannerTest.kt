package app.nayti.search.engine.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalQueryPlannerTest {
    private val planner = MultimodalQueryPlanner()

    @Test
    fun exactIdentifiersNamesAndQuotesNeverOpenVisualRetriever() {
        mapOf(
            "№ АБ-123/45" to MultimodalQueryIntent.IDENTIFIER,
            "15.07.2026" to MultimodalQueryIntent.IDENTIFIER,
            "Иванов Сергей Петрович" to MultimodalQueryIntent.PERSON_NAME,
            "\"амоксициллин 500 мг\"" to MultimodalQueryIntent.QUOTED_EXACT,
        ).forEach { (query, expected) ->
            val plan = planner.plan(query)
            assertEquals(expected, plan.intent)
            assertFalse(plan.usesVisualRetriever)
        }
    }

    @Test
    fun routesScenesDocumentsAndAmbiguousLanguageConservatively() {
        assertEquals(MultimodalQueryIntent.VISUAL_SCENE, planner.plan("рыжий кот на синем диване").intent)
        assertEquals(MultimodalQueryIntent.VISUAL_SCENE, planner.plan("red car on a road").intent)
        assertEquals(MultimodalQueryIntent.VISUAL_SCENE, planner.plan("Red Car").intent)
        assertEquals(MultimodalQueryIntent.VISUAL_SCENE, planner.plan("Рыжий Кот").intent)
        assertEquals(MultimodalQueryIntent.TEXT_CONCEPT, planner.plan("договор аренды").intent)
        assertEquals(MultimodalQueryIntent.TEXT_CONCEPT, planner.plan("medical receipt").intent)
        assertEquals(MultimodalQueryIntent.TEXT_CONCEPT, planner.plan("Medical Receipt").intent)
        assertEquals(MultimodalQueryIntent.PERSON_NAME, planner.plan("Bill Gates").intent)
        assertEquals(MultimodalQueryIntent.BROAD_HYBRID, planner.plan("скриншот настроек Wi-Fi").intent)
        assertEquals(MultimodalQueryIntent.BROAD_HYBRID, planner.plan("мой прошлогодний отпуск").intent)
        assertTrue(planner.plan("мой прошлогодний отпуск").usesVisualRetriever)
    }
}
