package app.nayti.indexer

import app.nayti.search.engine.fusion.MultimodalQueryIntent
import app.nayti.search.engine.fusion.MultimodalQueryPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedSearchRoutingTest {
    private val planner = MultimodalQueryPlanner()
    private val literalOnly = SearchChannelSelection(true, false, false)

    @Test
    fun uiAllAndOmittedSelectionHaveTheSameContractForEveryIntent() {
        MultimodalQueryIntent.entries.forEach { intent ->
            assertEquals(
                resolveUnifiedSearchChannels(intent, null),
                resolveUnifiedSearchChannels(intent, SearchChannelSelection.All),
            )
        }
    }

    @Test
    fun exactSyntaxDoesNotRequestApproximateFallbackEvenWhenNothingMatches() {
        listOf("№ АБ-123/45", "ZX-987654", "\"номер заказа\"", "\"red car\"").forEach { query ->
            assertEquals(query, literalOnly, resolveUnifiedSearchChannels(planner.plan(query).intent, SearchChannelSelection.All))
        }
    }

    @Test
    fun allSixReducedSelectionsRemainExplicitForEveryIntent() {
        (1..6).forEach { mask ->
            val selection = SearchChannelSelection(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            MultimodalQueryIntent.entries.forEach { intent ->
                assertEquals(selection, resolveUnifiedSearchChannels(intent, selection))
            }
        }
    }

    @Test
    fun capitalizationAndAmbiguousPrefixHeuristicsDoNotDisableNeuralChannels() {
        listOf("онлайн тест", "Онлайн Тест", "Иван Петров", "морковь", "актёр", "catalog", "red car").forEach { query ->
            assertEquals(query, SearchChannelSelection.All, resolveUnifiedSearchChannels(planner.plan(query).intent, null))
        }
    }
}
