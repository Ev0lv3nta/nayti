package app.nayti.search.engine.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalTextNormalizerTest {
    private val normalizer = LexicalTextNormalizer()

    @Test
    fun preservesDisplayButBuildsSeparateCanonicalStemAndIdentifierForms() {
        val forms = normalizer.normalize(listOf("  СЧЁТАМИ…\u0000  ", "Café receipts", "АВС-123"))

        assertEquals("СЧЁТАМИ…\nCafé receipts\nАВС-123", forms.display)
        assertEquals("счетами café receipts авс 123", forms.canonical)
        assertEquals("счет café receipt авс 123", forms.stems)
        assertEquals("авс 123 авс123 abc 123 abc123", forms.identifiers)
    }

    @Test
    fun identifierFoldingIsRestrictedToTokensContainingDigits() {
        assertEquals("", normalizer.identifiers("АВС Иванов"))
        assertEquals("а 123 а123 a 123 a123", normalizer.identifiers("А-123"))
        assertTrue(normalizer.identifiers("номер 12/3456").contains("12 3456"))
    }

    @Test
    fun canonicalNormalizationDoesNotOverwriteMeaningfulUnicodeLetters() {
        assertEquals("café йод елка", normalizer.canonical("Café, ЙОД, Ёлка"))
    }
}
