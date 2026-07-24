package app.nayti.indexer

enum class SearchChannel {
    OCR_LITERAL,
    OCR_SEMANTIC,
    VISUAL,
}

/**
 * Explicit retriever selection for one query.
 *
 * Search channels share the same durable index, so changing this value never rebuilds or mutates
 * prepared library data.
 */
data class SearchChannelSelection(
    val ocrLiteral: Boolean = true,
    val ocrSemantic: Boolean = true,
    val visual: Boolean = true,
) {
    init {
        require(hasAny) { "At least one search channel must be selected" }
    }

    val hasAny: Boolean
        get() = ocrLiteral || ocrSemantic || visual

    val usesText: Boolean
        get() = ocrLiteral || ocrSemantic

    fun set(channel: SearchChannel, enabled: Boolean): SearchChannelSelection {
        if (!enabled && isEnabled(channel) && enabledCount == 1) return this
        val updated =
            when (channel) {
                SearchChannel.OCR_LITERAL -> copy(ocrLiteral = enabled)
                SearchChannel.OCR_SEMANTIC -> copy(ocrSemantic = enabled)
                SearchChannel.VISUAL -> copy(visual = enabled)
            }
        return updated
    }

    fun isEnabled(channel: SearchChannel): Boolean =
        when (channel) {
            SearchChannel.OCR_LITERAL -> ocrLiteral
            SearchChannel.OCR_SEMANTIC -> ocrSemantic
            SearchChannel.VISUAL -> visual
        }

    private val enabledCount: Int
        get() = listOf(ocrLiteral, ocrSemantic, visual).count { it }

    companion object {
        val All = SearchChannelSelection()
    }
}
