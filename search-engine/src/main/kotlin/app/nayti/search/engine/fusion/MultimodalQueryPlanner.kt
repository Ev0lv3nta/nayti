package app.nayti.search.engine.fusion

import app.nayti.search.engine.lexical.LexicalIntent
import app.nayti.search.engine.lexical.LexicalQueryPlanner

enum class MultimodalQueryIntent {
    QUOTED_EXACT,
    IDENTIFIER,
    PERSON_NAME,
    TEXT_CONCEPT,
    VISUAL_SCENE,
    BROAD_HYBRID,
}

data class MultimodalQueryPlan(
    val intent: MultimodalQueryIntent,
    val usesVisualRetriever: Boolean,
)

/** Conservative deterministic routing; uncertain ordinary language searches both semantic spaces. */
class MultimodalQueryPlanner(
    private val lexical: LexicalQueryPlanner = LexicalQueryPlanner(),
) {
    fun plan(query: String): MultimodalQueryPlan {
        val lexicalPlan = lexical.plan(query)
        val intent =
            when (lexicalPlan.intent) {
                LexicalIntent.EMPTY -> error("Empty query has no multimodal intent")
                LexicalIntent.QUOTED_PHRASE -> MultimodalQueryIntent.QUOTED_EXACT
                LexicalIntent.IDENTIFIER -> MultimodalQueryIntent.IDENTIFIER
                LexicalIntent.PERSON_NAME -> classifyNameCandidate(lexicalPlan.canonicalTerms)
                LexicalIntent.ORDINARY_TEXT -> classifyOrdinary(lexicalPlan.canonicalTerms)
            }
        return MultimodalQueryPlan(
            intent = intent,
            usesVisualRetriever =
                intent in setOf(
                    MultimodalQueryIntent.TEXT_CONCEPT,
                    MultimodalQueryIntent.VISUAL_SCENE,
                    MultimodalQueryIntent.BROAD_HYBRID,
                ),
        )
    }

    private fun classifyOrdinary(terms: List<String>): MultimodalQueryIntent {
        val (visualSignals, documentSignals) = signalCounts(terms)
        return when {
            visualSignals > 0 && documentSignals == 0 -> MultimodalQueryIntent.VISUAL_SCENE
            documentSignals > 0 && visualSignals == 0 -> MultimodalQueryIntent.TEXT_CONCEPT
            else -> MultimodalQueryIntent.BROAD_HYBRID
        }
    }

    private fun classifyNameCandidate(terms: List<String>): MultimodalQueryIntent {
        val (visualSignals, documentSignals) = signalCounts(terms)
        return when {
            visualSignals >= 2 && documentSignals == 0 -> MultimodalQueryIntent.VISUAL_SCENE
            documentSignals >= 2 && visualSignals == 0 -> MultimodalQueryIntent.TEXT_CONCEPT
            else -> MultimodalQueryIntent.PERSON_NAME
        }
    }

    private fun signalCounts(terms: List<String>) =
        SignalCounts(
            visual = terms.count { term -> VisualPrefixes.any(term::startsWith) },
            document = terms.count { term -> DocumentPrefixes.any(term::startsWith) },
        )

    private data class SignalCounts(val visual: Int, val document: Int)

    private companion object {
        val VisualPrefixes =
            setOf(
                "автомоб", "бел", "велосип", "город", "гор", "дерев", "диван", "дорог", "животн",
                "здани", "зелен", "кот", "кош", "красн", "лес", "машин", "мор", "неб", "облак",
                "пляж", "птиц", "рыж", "син", "скриншот", "собак", "стол", "улиц", "цвет", "человек",
                "animal", "beach", "bike", "bird", "blue", "building", "car", "cat", "city", "cloud",
                "dog", "forest", "green", "mountain", "person", "red", "road", "sea", "sky", "sofa",
                "screenshot", "street", "table", "tree", "white",
            )
        val DocumentPrefixes =
            setOf(
                "акт", "анализ", "билет", "выписк", "диагноз", "договор", "документ", "инструкц", "медицин",
                "квитанц", "контракт", "лекар", "настро", "паспорт", "письм", "рецепт", "счет", "счёт",
                "справк", "талон", "текст", "чек", "agreement", "bill", "contract", "diagnosis", "document",
                "instruction", "invoice", "letter", "medical", "passport", "receipt", "recipe", "settings", "text", "ticket",
            )
    }
}
