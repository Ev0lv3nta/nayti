package app.nayti.indexer

/** Exact content-token counter supplied by the active semantic model tokenizer. */
fun interface SemanticTokenCounter {
    fun count(text: String): Int
}

data class SemanticOcrLine(
    val ordinal: Int,
    val displayText: String,
    val confidenceMicros: Int,
)

enum class OcrSemanticChunkKind {
    HEADER,
    BODY,
}

/**
 * Model-independent chunk metadata. Geometry remains attached to the referenced OCR line ordinals.
 */
data class OcrSemanticChunkDraft(
    val ordinal: Int,
    val kind: OcrSemanticChunkKind,
    val displayText: String,
    val contentTokenCount: Int,
    val lineOrdinals: List<Int>,
    val firstLineOrdinal: Int,
    val lastLineOrdinal: Int,
    val meanConfidenceMicros: Int,
    val reliableAlphabeticWordCount: Int,
    val chunkingVersion: String,
)

/**
 * Produces deterministic, bounded semantic chunks without modifying literal OCR evidence.
 *
 * The planner deliberately depends only on an exact token-count oracle. The active USER2 tokenizer
 * owns tokenization; this class owns stable document structure, overlap and provenance.
 */
class OcrSemanticChunkPlanner(
    private val tokenCounter: SemanticTokenCounter,
) {
    fun plan(lines: List<SemanticOcrLine>): List<OcrSemanticChunkDraft> {
        validateLines(lines)
        if (lines.isEmpty()) return emptyList()

        val words = lines.flatMap(::wordsForLine)
        require(words.size <= MaximumWords) { "OCR document exceeds the semantic word limit" }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<OcrSemanticChunkDraft>()
        val headerLineOrdinals = lines.take(HeaderLineLimit).mapTo(mutableSetOf(), SemanticOcrLine::ordinal)
        val headerWords = words.takeWhile { word -> word.lineOrdinal in headerLineOrdinals }
        firstWindow(headerWords)?.let { window ->
            createDraft(OcrSemanticChunkKind.HEADER, window.words, window.tokenCount, chunks.size)
                ?.let(chunks::add)
        }

        var start = 0
        while (start < words.size && chunks.size < MaximumChunks) {
            val window = largestWindow(words, start) ?: run {
                start += 1
                continue
            }
            val draft = createDraft(OcrSemanticChunkKind.BODY, window.words, window.tokenCount, chunks.size)
            if (draft == null) {
                start = window.endExclusive
                continue
            }

            chunks += draft
            if (window.endExclusive == words.size) break
            start = overlapStart(words, start, window.endExclusive)
                .takeIf { it > start }
                ?: window.endExclusive
        }
        return chunks
    }

    private fun firstWindow(words: List<Word>): Window? {
        var start = 0
        while (start < words.size) {
            largestWindow(words, start)?.let { return it }
            start += 1
        }
        return null
    }

    private fun largestWindow(words: List<Word>, start: Int): Window? {
        if (start >= words.size) return null
        var low = start + 1
        var high = words.size
        var bestEnd = -1
        var bestCount = -1
        while (low <= high) {
            val middle = low + (high - low) / 2
            val tokenCount = checkedTokenCount(render(words.subList(start, middle)))
            if (tokenCount <= MaximumContentTokens) {
                bestEnd = middle
                bestCount = tokenCount
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (bestEnd < 0) return null
        return Window(words.subList(start, bestEnd).toList(), bestEnd, bestCount)
    }

    private fun overlapStart(words: List<Word>, start: Int, endExclusive: Int): Int {
        var low = start + 1
        var high = endExclusive - 1
        var selected = endExclusive
        while (low <= high) {
            val middle = low + (high - low) / 2
            val tokenCount = checkedTokenCount(render(words.subList(middle, endExclusive)))
            if (tokenCount <= OverlapTokens) {
                selected = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return selected
    }

    private fun createDraft(
        kind: OcrSemanticChunkKind,
        words: List<Word>,
        tokenCount: Int,
        ordinal: Int,
    ): OcrSemanticChunkDraft? {
        val reliableCount = words.count(Word::isReliableAlphabetic)
        if (reliableCount < MinimumReliableAlphabeticWords) return null

        val lineOrdinals = words.map(Word::lineOrdinal).distinct()
        val confidences = words.distinctBy(Word::lineOrdinal).map(Word::confidenceMicros)
        return OcrSemanticChunkDraft(
            ordinal = ordinal,
            kind = kind,
            displayText = render(words),
            contentTokenCount = tokenCount,
            lineOrdinals = lineOrdinals,
            firstLineOrdinal = lineOrdinals.first(),
            lastLineOrdinal = lineOrdinals.last(),
            meanConfidenceMicros = confidences.sumOf(Int::toLong).div(confidences.size).toInt(),
            reliableAlphabeticWordCount = reliableCount,
            chunkingVersion = ChunkingVersion,
        )
    }

    private fun wordsForLine(line: SemanticOcrLine): List<Word> =
        Whitespace.findAll(line.displayText.trim())
            .map(MatchResult::value)
            .filter(String::isNotBlank)
            .map { text ->
                Word(
                    text = text,
                    lineOrdinal = line.ordinal,
                    confidenceMicros = line.confidenceMicros,
                    isReliableAlphabetic =
                        line.confidenceMicros >= ReliableConfidenceMicros &&
                            text.count(Char::isLetter) >= MinimumLettersPerReliableWord,
                )
            }
            .toList()

    private fun render(words: List<Word>): String =
        buildString {
            words.forEachIndexed { index, word ->
                if (index > 0) append(if (words[index - 1].lineOrdinal == word.lineOrdinal) ' ' else '\n')
                append(word.text)
            }
        }

    private fun checkedTokenCount(text: String): Int =
        tokenCounter.count(text).also { count ->
            require(count in 0..MaximumTokenizerResult) { "Semantic tokenizer returned an invalid token count" }
        }

    private fun validateLines(lines: List<SemanticOcrLine>) {
        require(lines.size <= MaximumLines) { "OCR document exceeds the semantic line limit" }
        var previousOrdinal = -1
        lines.forEach { line ->
            require(line.ordinal >= 0 && line.ordinal > previousOrdinal) {
                "OCR line ordinals must be unique and strictly increasing"
            }
            require(line.displayText.length <= MaximumLineCharacters) {
                "OCR line exceeds the semantic character limit"
            }
            require(line.confidenceMicros in 0..Micros) { "OCR confidence is outside fixed-point range" }
            previousOrdinal = line.ordinal
        }
    }

    private data class Word(
        val text: String,
        val lineOrdinal: Int,
        val confidenceMicros: Int,
        val isReliableAlphabetic: Boolean,
    )

    private data class Window(
        val words: List<Word>,
        val endExclusive: Int,
        val tokenCount: Int,
    )

    companion object {
        const val ChunkingVersion = "ocr-semantic-chunks-v1"
        const val MaximumContentTokens = 96
        const val OverlapTokens = 24
        const val HeaderLineLimit = 8
        const val MaximumChunks = 32
        const val MinimumReliableAlphabeticWords = 3
        const val ReliableConfidenceMicros = 450_000

        private const val MinimumLettersPerReliableWord = 2
        private const val MaximumLines = 512
        private const val MaximumWords = 8_192
        private const val MaximumLineCharacters = 2_048
        private const val MaximumTokenizerResult = 4_096
        private const val Micros = 1_000_000
        private val Whitespace = Regex("\\S+")
    }
}
