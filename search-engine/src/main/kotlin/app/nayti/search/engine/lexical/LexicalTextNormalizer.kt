package app.nayti.search.engine.lexical

import java.text.Normalizer
import java.util.Locale

data class LexicalTextForms(
    val display: String,
    val canonical: String,
    val stems: String,
    val identifiers: String,
)

class LexicalTextNormalizer {
    fun normalize(regions: List<String>): LexicalTextForms {
        require(regions.size <= MaximumRegions)
        val display =
            regions.asSequence()
                .map(::displayLine)
                .filter(String::isNotEmpty)
                .joinToString("\n")
        require(display.length <= MaximumDocumentCharacters)
        val canonical = canonical(display)
        val tokens = wordTokens(canonical)
        return LexicalTextForms(
            display = display,
            canonical = canonical,
            stems = tokens.map(::lightStem).filter(String::isNotEmpty).joinToString(" "),
            identifiers = identifiers(display),
        )
    }

    fun displayLine(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString("")
            .replace(Whitespace, " ")
            .trim()

    fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .map { character -> if (character.isLetterOrDigit()) character else ' ' }
            .joinToString("")
            .replace(Whitespace, " ")
            .trim()

    fun wordTokens(canonical: String): List<String> {
        require(canonical.length <= MaximumDocumentCharacters)
        return Word.findAll(canonical).map(MatchResult::value).take(MaximumTokens).toList()
    }

    fun lightStem(token: String): String {
        if (token.length < MinimumStemLength || token.any(Char::isDigit)) return token
        return when {
            token.all(::isCyrillic) -> stripSuffix(token, RussianSuffixes)
            token.all { character -> character in 'a'..'z' } -> englishStem(token)
            else -> token
        }
    }

    fun identifiers(value: String): String {
        require(value.length <= MaximumDocumentCharacters)
        val forms = linkedSetOf<String>()
        IdentifierCandidate.findAll(Normalizer.normalize(value, Normalizer.Form.NFKC)).forEach { match ->
            val candidate = match.value
            if (!candidate.any(Char::isDigit)) return@forEach
            val groups = candidate.split(IdentifierSeparator).filter(String::isNotEmpty)
            if (groups.isEmpty()) return@forEach
            val canonicalGroups = groups.map { group -> group.lowercase(Locale.ROOT).replace('ё', 'е') }
            addIdentifierForms(forms, canonicalGroups)
            val foldedGroups = canonicalGroups.map(::foldIdentifierConfusables)
            if (foldedGroups != canonicalGroups) addIdentifierForms(forms, foldedGroups)
        }
        return forms.joinToString(" ")
    }

    private fun addIdentifierForms(target: MutableSet<String>, groups: List<String>) {
        target += groups.joinToString(" ")
        if (groups.size > 1) target += groups.joinToString("")
    }

    private fun foldIdentifierConfusables(value: String): String =
        buildString(value.length) {
            value.forEach { character -> append(ConfusableToLatin[character] ?: character) }
        }

    private fun stripSuffix(token: String, suffixes: List<String>): String {
        val suffix = suffixes.firstOrNull { candidate ->
            token.endsWith(candidate) && token.length - candidate.length >= MinimumStemRemainder
        } ?: return token
        return token.dropLast(suffix.length)
    }

    private fun englishStem(token: String): String {
        val withoutPossessive = token.removeSuffix("'s")
        return stripSuffix(withoutPossessive, EnglishSuffixes)
    }

    private fun isCyrillic(character: Char): Boolean =
        Character.UnicodeBlock.of(character) in CyrillicBlocks

    companion object {
        const val NormalizerVersion = "lexical-normalizer-v1"
        const val StemmerVersion = "light-ru-en-v1"
        const val IdentifierVersion = "identifier-fold-v1"
        const val MaximumDocumentCharacters = 262_144
        const val MaximumRegions = 4_096
        private const val MaximumTokens = 32_768
        private const val MinimumStemLength = 5
        private const val MinimumStemRemainder = 3
        private val Whitespace = Regex("\\s+")
        private val Word = Regex("[\\p{L}\\p{N}]+")
        private val IdentifierCandidate = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/#-]{1,63}")
        private val IdentifierSeparator = Regex("[^\\p{L}\\p{N}]+")
        private val CyrillicBlocks =
            setOf(
                Character.UnicodeBlock.CYRILLIC,
                Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY,
                Character.UnicodeBlock.CYRILLIC_EXTENDED_A,
                Character.UnicodeBlock.CYRILLIC_EXTENDED_B,
            )
        private val RussianSuffixes =
            listOf(
                "иями",
                "ями",
                "ами",
                "ого",
                "ему",
                "ому",
                "ыми",
                "ими",
                "иях",
                "ах",
                "ях",
                "ий",
                "ый",
                "ой",
                "ая",
                "яя",
                "ое",
                "ее",
                "ие",
                "ые",
                "ую",
                "юю",
                "ов",
                "ев",
                "ам",
                "ям",
                "ы",
                "и",
                "а",
                "я",
                "у",
                "ю",
                "е",
                "о",
            )
        private val EnglishSuffixes = listOf("ingly", "edly", "ing", "ed", "ies", "es", "s")
        private val ConfusableToLatin =
            mapOf(
                'а' to 'a',
                'в' to 'b',
                'е' to 'e',
                'к' to 'k',
                'м' to 'm',
                'н' to 'h',
                'о' to 'o',
                'р' to 'p',
                'с' to 'c',
                'т' to 't',
                'у' to 'y',
                'х' to 'x',
            )
    }
}
