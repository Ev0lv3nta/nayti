package app.nayti.indexer

import app.nayti.search.engine.fusion.MultimodalQueryIntent

/**
 * All enables ordinary hybrid search, with exact syntax taking precedence over approximate matches.
 * A reduced selection is an explicit request and is never rewritten. Capitalization/name heuristics
 * may influence ranking, but are not strong enough to silently disable semantic retrieval.
 */
internal fun resolveUnifiedSearchChannels(
    intent: MultimodalQueryIntent,
    requested: SearchChannelSelection?,
): SearchChannelSelection {
    if (requested != null && requested != SearchChannelSelection.All) return requested
    return when (intent) {
        MultimodalQueryIntent.IDENTIFIER,
        MultimodalQueryIntent.QUOTED_EXACT,
        -> SearchChannelSelection(ocrLiteral = true, ocrSemantic = false, visual = false)
        else -> SearchChannelSelection.All
    }
}
