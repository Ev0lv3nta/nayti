package app.nayti.search.engine.lexical

enum class LexicalIntent {
    EMPTY,
    QUOTED_PHRASE,
    IDENTIFIER,
    PERSON_NAME,
    ORDINARY_TEXT,
}

data class LexicalQueryPlan(
    val original: String,
    val intent: LexicalIntent,
    val canonicalTerms: List<String>,
    val quotedPhrases: List<String>,
    val identifierForms: List<String>,
    val ftsMatch: String?,
    val trigramMatch: String?,
    val fuzzyTerms: List<String>,
)

class LexicalQueryPlanner(
    private val normalizer: LexicalTextNormalizer = LexicalTextNormalizer(),
) {
    fun plan(input: String): LexicalQueryPlan {
        require(input.length <= MaximumQueryCharacters)
        val original = input.trim()
        if (original.isEmpty()) return emptyPlan(input)
        val parsed = parseQuoted(original)
        val phrases = parsed.quoted.map(normalizer::canonical).filter(String::isNotEmpty).take(MaximumTerms)
        val unquotedCanonical = normalizer.canonical(parsed.unquoted.joinToString(" "))
        val terms = normalizer.wordTokens(unquotedCanonical).filter { it.length <= MaximumTermCharacters }.take(MaximumTerms)
        val identifierText = normalizer.identifiers(original)
        val identifierForms = identifierForms(identifierText)
        val intent = classify(original, phrases, terms, identifierForms)
        val match = compileFts(phrases, terms, identifierForms, intent)
        val fuzzyTerms =
            if (intent in setOf(LexicalIntent.ORDINARY_TEXT, LexicalIntent.PERSON_NAME)) {
                terms.filter { term -> term.length in MinimumFuzzyLength..MaximumFuzzyLength }
            } else {
                emptyList()
            }
        return LexicalQueryPlan(
            original = input,
            intent = intent,
            canonicalTerms = terms,
            quotedPhrases = phrases,
            identifierForms = identifierForms,
            ftsMatch = match,
            trigramMatch = compileTrigrams(fuzzyTerms),
            fuzzyTerms = fuzzyTerms,
        )
    }

    private fun classify(
        original: String,
        phrases: List<String>,
        terms: List<String>,
        identifiers: List<String>,
    ): LexicalIntent =
        when {
            phrases.isNotEmpty() -> LexicalIntent.QUOTED_PHRASE
            identifiers.isNotEmpty() -> LexicalIntent.IDENTIFIER
            looksLikePersonName(original, terms) -> LexicalIntent.PERSON_NAME
            terms.isNotEmpty() -> LexicalIntent.ORDINARY_TEXT
            else -> LexicalIntent.EMPTY
        }

    private fun compileFts(
        phrases: List<String>,
        terms: List<String>,
        identifiers: List<String>,
        intent: LexicalIntent,
    ): String? {
        if (intent == LexicalIntent.EMPTY) return null
        val clauses = mutableListOf<String>()
        phrases.forEach { phrase -> clauses += "canonical : ${quote(phrase)}" }
        if (intent == LexicalIntent.IDENTIFIER) {
            val alternatives = identifiers.map { form -> "identifiers : ${quote(form)}" }
            if (alternatives.isNotEmpty()) clauses += alternatives.joinToString(" OR ", "(", ")")
        } else {
            terms.forEach { term ->
                val stem = normalizer.lightStem(term)
                clauses +=
                    if (stem != term) {
                        "(canonical : ${quote(term)} OR stems : ${quote(stem)})"
                    } else {
                        "canonical : ${quote(term)}"
                    }
            }
        }
        return clauses.distinct().joinToString(" AND ").takeIf(String::isNotEmpty)
    }

    private fun compileTrigrams(terms: List<String>): String? {
        val grams =
            terms.asSequence()
                .flatMap { term -> term.windowed(3, 1, partialWindows = false).asSequence() }
                .distinct()
                .take(MaximumTrigrams)
                .toList()
        return grams.takeIf(List<String>::isNotEmpty)?.joinToString(" OR ") { gram -> quote(gram) }
    }

    private fun identifierForms(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val tokens = value.split(' ').filter(String::isNotBlank)
        val forms = linkedSetOf<String>()
        if (tokens.isNotEmpty()) forms += tokens.joinToString(" ")
        tokens.filter { token -> token.any(Char::isDigit) && token.any(Char::isLetter) }.forEach(forms::add)
        return forms.take(MaximumIdentifierForms)
    }

    private fun looksLikePersonName(original: String, terms: List<String>): Boolean {
        if (terms.size !in 2..4 || terms.any { term -> term.length < 2 || term.any(Char::isDigit) }) return false
        val visibleWords = Regex("[\\p{L}]+(?:-[\\p{L}]+)?").findAll(original).map(MatchResult::value).toList()
        return visibleWords.size == terms.size && visibleWords.count { word -> word.firstOrNull()?.isUpperCase() == true } >= 2
    }

    private fun parseQuoted(value: String): ParsedQuery {
        val quoted = mutableListOf<String>()
        val unquoted = mutableListOf<String>()
        val current = StringBuilder()
        var insideQuote = false
        value.forEach { character ->
            if (character == '"') {
                val part = current.toString()
                current.setLength(0)
                if (insideQuote) quoted += part else unquoted += part
                insideQuote = !insideQuote
            } else {
                current.append(character)
            }
        }
        val tail = current.toString()
        if (insideQuote) unquoted += tail else unquoted += tail
        return ParsedQuery(quoted, unquoted)
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun emptyPlan(input: String) =
        LexicalQueryPlan(input, LexicalIntent.EMPTY, emptyList(), emptyList(), emptyList(), null, null, emptyList())

    private data class ParsedQuery(
        val quoted: List<String>,
        val unquoted: List<String>,
    )

    companion object {
        const val MaximumQueryCharacters = 512
        const val MaximumTerms = 16
        const val MaximumTermCharacters = 64
        const val MaximumTrigrams = 128
        const val MaximumIdentifierForms = 8
        const val MinimumFuzzyLength = 4
        const val MaximumFuzzyLength = 48
    }
}
