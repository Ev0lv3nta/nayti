package app.nayti.search.engine.lexical

data class FuzzyTokenMatch(
    val queryTerm: String,
    val documentToken: String,
    val distance: Int,
)

object BoundedFuzzyMatcher {
    fun bestMatches(
        queryTerms: List<String>,
        documentCanonical: String,
        maximumDistance: Int = 2,
    ): List<FuzzyTokenMatch> {
        require(queryTerms.size <= LexicalQueryPlanner.MaximumTerms)
        require(documentCanonical.length <= LexicalTextNormalizer.MaximumDocumentCharacters)
        require(maximumDistance in 0..MaximumSupportedDistance)
        val tokens = Token.findAll(documentCanonical).map(MatchResult::value).take(MaximumDocumentTokens).toList()
        return queryTerms.mapNotNull { query ->
            require(query.length <= LexicalQueryPlanner.MaximumFuzzyLength)
            tokens.asSequence()
                .filter { token -> token.length <= MaximumComparedTokenLength }
                .mapNotNull { token ->
                    val distance = distance(query, token, maximumDistance)
                    if (distance <= maximumDistance) FuzzyTokenMatch(query, token, distance) else null
                }
                .minWithOrNull(compareBy(FuzzyTokenMatch::distance, FuzzyTokenMatch::documentToken))
        }
    }

    fun distance(left: String, right: String, maximumDistance: Int): Int {
        require(maximumDistance in 0..MaximumSupportedDistance)
        require(left.length <= MaximumComparedTokenLength && right.length <= MaximumComparedTokenLength)
        if (left == right) return 0
        if (kotlin.math.abs(left.length - right.length) > maximumDistance) return maximumDistance + 1
        if (left.isEmpty() || right.isEmpty()) {
            val result = maxOf(left.length, right.length)
            return if (result <= maximumDistance) result else maximumDistance + 1
        }

        var previousPrevious = IntArray(right.length + 1) { it }
        var previous = previousPrevious.copyOf()
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            var rowMinimum = current[0]
            for (rightIndex in right.indices) {
                val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
                val insertion = current[rightIndex] + 1
                val deletion = previous[rightIndex + 1] + 1
                var value = minOf(substitution, insertion, deletion)
                if (
                    leftIndex > 0 &&
                    rightIndex > 0 &&
                    left[leftIndex] == right[rightIndex - 1] &&
                    left[leftIndex - 1] == right[rightIndex]
                ) {
                    value = minOf(value, previousPrevious[rightIndex - 1] + 1)
                }
                current[rightIndex + 1] = value
                rowMinimum = minOf(rowMinimum, value)
            }
            if (rowMinimum > maximumDistance) return maximumDistance + 1
            previousPrevious = previous
            previous = current
        }
        val result = previous[right.length]
        return if (result <= maximumDistance) result else maximumDistance + 1
    }

    private val Token = Regex("[\\p{L}\\p{N}]+")
    private const val MaximumSupportedDistance = 3
    private const val MaximumComparedTokenLength = 64
    private const val MaximumDocumentTokens = 32_768
}
