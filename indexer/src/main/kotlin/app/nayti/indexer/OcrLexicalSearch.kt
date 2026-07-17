package app.nayti.indexer

import app.nayti.search.engine.lexical.BoundedFuzzyMatcher
import app.nayti.search.engine.lexical.FuzzyTokenMatch
import app.nayti.search.engine.lexical.LexicalIntent
import app.nayti.search.engine.lexical.LexicalQueryPlan
import app.nayti.search.engine.lexical.LexicalQueryPlanner
import app.nayti.storage.OcrCandidateSnapshot
import app.nayti.storage.OcrDao
import app.nayti.storage.OcrDocumentEntity
import app.nayti.storage.OcrRegionEntity

enum class LexicalEvidence {
    EXACT_IDENTIFIER,
    QUOTED_PHRASE,
    PERSON_NAME,
    LITERAL_TEXT,
    FUZZY_TEXT,
}

data class OcrLexicalHit(
    val assetId: Long,
    val rank: Int,
    val evidence: LexicalEvidence,
    val displaySnippet: String,
    val matchedRegionOrdinals: List<Int>,
    val publicationEpoch: Long,
)

data class OcrLexicalSearchResult(
    val intent: LexicalIntent,
    val capturedPublicationEpoch: Long,
    val hits: List<OcrLexicalHit>,
)

class OcrLexicalSearch(
    private val ocr: OcrDao,
    private val planner: LexicalQueryPlanner = LexicalQueryPlanner(),
) {
    suspend fun search(
        query: String,
        pipelineVersion: String,
        componentHash: String,
        limit: Int = DefaultLimit,
    ): OcrLexicalSearchResult {
        require(limit in 1..MaximumResultLimit)
        val plan = planner.plan(query)
        if (plan.intent == LexicalIntent.EMPTY || (plan.ftsMatch == null && plan.trigramMatch == null)) {
            return OcrLexicalSearchResult(plan.intent, ocr.publicationClock()?.lastEpoch ?: 0, emptyList())
        }
        val snapshot =
            ocr.candidateSnapshot(
                lexicalMatchQuery = plan.ftsMatch,
                trigramMatchQuery = plan.trigramMatch,
                pipelineVersion = pipelineVersion,
                componentHash = componentHash,
                limit = OcrDao.MaximumCandidates,
            )
        return assemble(plan, snapshot, limit)
    }

    suspend fun searchAtEpoch(
        query: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int = DefaultLimit,
    ): OcrLexicalSearchResult {
        require(limit in 1..MaximumResultLimit)
        require(maximumPublicationEpoch >= 0)
        val plan = planner.plan(query)
        if (plan.intent == LexicalIntent.EMPTY || (plan.ftsMatch == null && plan.trigramMatch == null)) {
            return OcrLexicalSearchResult(plan.intent, maximumPublicationEpoch, emptyList())
        }
        val snapshot =
            ocr.candidateSnapshotAt(
                lexicalMatchQuery = plan.ftsMatch,
                trigramMatchQuery = plan.trigramMatch,
                pipelineVersion = pipelineVersion,
                componentHash = componentHash,
                maximumPublicationEpoch = maximumPublicationEpoch,
                limit = OcrDao.MaximumCandidates,
            )
        return assemble(plan, snapshot, limit)
    }

    internal fun assemble(
        plan: LexicalQueryPlan,
        snapshot: OcrCandidateSnapshot,
        limit: Int,
    ): OcrLexicalSearchResult {
        require(limit in 1..MaximumResultLimit)
        val documents = snapshot.documents.associateBy(OcrDocumentEntity::assetId)
        val regions = snapshot.regions.groupBy(OcrRegionEntity::assetId)
        val literalIds = snapshot.lexicalCandidates.mapNotNull { candidate ->
            candidate.assetId.takeIf(documents::containsKey)
        }.distinct()
        val literalSet = literalIds.toSet()
        val ranked = mutableListOf<UnrankedHit>()
        literalIds.forEachIndexed { position, assetId ->
            val document = checkNotNull(documents[assetId])
            ranked +=
                evidence(
                    assetId = assetId,
                    evidence = plan.literalEvidence(),
                    document = document,
                    regions = regions[assetId].orEmpty(),
                    plan = plan,
                    fuzzyMatches = emptyList(),
                    group = 0,
                    order = position,
                )
        }
        snapshot.trigramCandidates.forEachIndexed { position, candidate ->
            if (candidate.assetId in literalSet) return@forEachIndexed
            val document = documents[candidate.assetId] ?: return@forEachIndexed
            val matches = fuzzyMatches(plan, document.canonicalText)
            if (matches.size != plan.fuzzyTerms.size || matches.isEmpty()) return@forEachIndexed
            ranked +=
                evidence(
                    assetId = candidate.assetId,
                    evidence = LexicalEvidence.FUZZY_TEXT,
                    document = document,
                    regions = regions[candidate.assetId].orEmpty(),
                    plan = plan,
                    fuzzyMatches = matches,
                    group = 1,
                    order = matches.sumOf(FuzzyTokenMatch::distance) * FuzzyDistanceStride + position,
                )
        }
        val hits =
            ranked.sortedWith(compareBy(UnrankedHit::group, UnrankedHit::order, UnrankedHit::assetId))
                .take(limit)
                .mapIndexed { index, hit ->
                    OcrLexicalHit(
                        assetId = hit.assetId,
                        rank = index + 1,
                        evidence = hit.evidence,
                        displaySnippet = hit.displaySnippet,
                        matchedRegionOrdinals = hit.matchedRegionOrdinals,
                        publicationEpoch = hit.publicationEpoch,
                    )
                }
        return OcrLexicalSearchResult(plan.intent, snapshot.maximumPublicationEpoch, hits)
    }

    private fun evidence(
        assetId: Long,
        evidence: LexicalEvidence,
        document: OcrDocumentEntity,
        regions: List<OcrRegionEntity>,
        plan: LexicalQueryPlan,
        fuzzyMatches: List<FuzzyTokenMatch>,
        group: Int,
        order: Int,
    ): UnrankedHit {
        val relevantTokens =
            (plan.canonicalTerms +
                plan.quotedPhrases.flatMap { phrase -> phrase.split(' ') } +
                fuzzyMatches.map(FuzzyTokenMatch::documentToken))
                .filter(String::isNotBlank)
                .toSet()
        val matchingRegions =
            regions.filter { region ->
                relevantTokens.any { token -> region.canonicalText.containsToken(token) }
            }.take(MaximumSnippetRegions)
        val snippet =
            matchingRegions.joinToString(" … ", transform = OcrRegionEntity::displayText)
                .ifBlank { document.displayText }
                .take(MaximumSnippetCharacters)
        return UnrankedHit(
            assetId = assetId,
            evidence = evidence,
            displaySnippet = snippet,
            matchedRegionOrdinals = matchingRegions.map(OcrRegionEntity::ordinal),
            publicationEpoch = document.publicationEpoch,
            group = group,
            order = order,
        )
    }

    private fun fuzzyMatches(plan: LexicalQueryPlan, documentCanonical: String): List<FuzzyTokenMatch> {
        if (plan.fuzzyTerms.isEmpty()) return emptyList()
        val matches = BoundedFuzzyMatcher.bestMatches(plan.fuzzyTerms, documentCanonical, MaximumFuzzyDistance)
        return matches.filter { match -> match.distance <= allowedDistance(match.queryTerm) }
    }

    private fun allowedDistance(term: String): Int =
        when (term.length) {
            in 0..5 -> 1
            in 6..12 -> 2
            else -> 3
        }

    private fun LexicalQueryPlan.literalEvidence(): LexicalEvidence =
        when (intent) {
            LexicalIntent.QUOTED_PHRASE -> LexicalEvidence.QUOTED_PHRASE
            LexicalIntent.IDENTIFIER -> LexicalEvidence.EXACT_IDENTIFIER
            LexicalIntent.PERSON_NAME -> LexicalEvidence.PERSON_NAME
            LexicalIntent.ORDINARY_TEXT -> LexicalEvidence.LITERAL_TEXT
            LexicalIntent.EMPTY -> error("Empty plan has no literal evidence")
        }

    private fun String.containsToken(token: String): Boolean =
        split(TokenSeparator).any { candidate -> candidate == token }

    private data class UnrankedHit(
        val assetId: Long,
        val evidence: LexicalEvidence,
        val displaySnippet: String,
        val matchedRegionOrdinals: List<Int>,
        val publicationEpoch: Long,
        val group: Int,
        val order: Int,
    )

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumFuzzyDistance = 3
        private const val FuzzyDistanceStride = 1_000
        private const val MaximumSnippetRegions = 2
        private const val MaximumSnippetCharacters = 240
        private val TokenSeparator = Regex("[^\\p{L}\\p{N}]+")
    }
}
