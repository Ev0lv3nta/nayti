package app.nayti.indexer

import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.ml.runtime.visual.Siglip2EmbeddingSpaceIdentity
import app.nayti.ml.runtime.visual.Siglip2TextOrtRuntime
import app.nayti.storage.QuerySnapshotLeaseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VisualTextSearchStatus {
    READY,
    NO_ACTIVE_SNAPSHOT,
    NO_VISUAL_MANIFEST,
}

data class VisualTextSearchResult(
    val status: VisualTextSearchStatus,
    val snapshotId: String?,
    val manifestRevision: String?,
    val accessRevision: Long?,
    val hits: List<VisualSimilarityHit>,
)

interface VisualTextQuerySession : AutoCloseable {
    val embeddingSpaceHash: String
    val dimension: Int
    fun encodeQuery(text: String): ByteArray
}

fun interface VisualTextQuerySessionFactory {
    suspend fun open(contract: VisualQueryContract): VisualTextQuerySession
}

class InstalledSiglip2TextQuerySessionFactory(
    private val resolver: InstalledOcrPackResolver,
    private val neuralLane: NeuralExecutionLane = NeuralExecutionLane(),
) : VisualTextQuerySessionFactory {
    override suspend fun open(contract: VisualQueryContract): VisualTextQuerySession {
        require(contract.dimension == Siglip2Contract.EmbeddingDimension)
        val pack = resolver.resolve(contract.packId, contract.packVersion)
        check(pack.componentHash == contract.packManifestSha256)
        val actualEmbeddingSpace =
            withContext(Dispatchers.IO) {
                Siglip2EmbeddingSpaceIdentity.calculate(pack.payloadDirectory.parent)
            }
        check(actualEmbeddingSpace == contract.embeddingSpaceHash)
        val permit = neuralLane.acquire()
        return try {
            val runtime =
                withContext(Dispatchers.Default) {
                    Siglip2TextOrtRuntime.open(pack.payloadDirectory)
                }
            Siglip2TextQuerySession(runtime, actualEmbeddingSpace, permit)
        } catch (failure: Throwable) {
            permit.close()
            throw failure
        }
    }
}

private class Siglip2TextQuerySession(
    private val runtime: Siglip2TextOrtRuntime,
    override val embeddingSpaceHash: String,
    private val permit: NeuralExecutionPermit,
) : VisualTextQuerySession {
    override val dimension: Int = Siglip2Contract.EmbeddingDimension

    override fun encodeQuery(text: String): ByteArray = runtime.encodeQuery(text).quantized

    override fun close() {
        try {
            runtime.close()
        } finally {
            permit.close()
        }
    }
}

/** Encodes natural-language queries in the active SigLIP2 space and scans the visual snapshot. */
class VisualTextSearch(
    private val similarity: VisualSimilaritySearch,
    private val sessions: VisualTextQuerySessionFactory,
) {
    suspend fun search(
        query: String,
        limit: Int = VisualSimilaritySearch.DefaultLimit,
    ): VisualTextSearchResult {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty() && normalizedQuery.length <= MaximumQueryCharacters)
        val result =
            similarity.searchEncoded(limit) { contract ->
                sessions.open(contract).use { session ->
                    check(
                        session.embeddingSpaceHash == contract.embeddingSpaceHash &&
                            session.dimension == contract.dimension,
                    )
                    session.encodeQuery(normalizedQuery).also { vector ->
                        check(vector.size == contract.dimension)
                    }
                }
            }
        return result.toTextResult()
    }

    internal suspend fun searchLeased(
        query: String,
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
    ): VisualTextSearchResult {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty() && normalizedQuery.length <= MaximumQueryCharacters)
        val result =
            similarity.searchEncodedLeased(limit, lease) { contract ->
                sessions.open(contract).use { session ->
                    check(
                        session.embeddingSpaceHash == contract.embeddingSpaceHash &&
                            session.dimension == contract.dimension,
                    )
                    session.encodeQuery(normalizedQuery).also { vector ->
                        check(vector.size == contract.dimension)
                    }
                }
            }
        return result.toTextResult()
    }

    private fun EncodedVisualSearchResult.toTextResult() =
        VisualTextSearchResult(
            status =
                when (status) {
                    VisualSimilaritySearchStatus.READY -> VisualTextSearchStatus.READY
                    VisualSimilaritySearchStatus.NO_ACTIVE_SNAPSHOT ->
                        VisualTextSearchStatus.NO_ACTIVE_SNAPSHOT
                    VisualSimilaritySearchStatus.NO_VISUAL_MANIFEST ->
                        VisualTextSearchStatus.NO_VISUAL_MANIFEST
                    VisualSimilaritySearchStatus.SOURCE_NOT_INDEXED ->
                        error("Encoded visual query cannot have a missing source asset")
                },
            snapshotId = snapshotId,
            manifestRevision = manifestRevision,
            accessRevision = accessRevision,
            hits = hits,
        )

    companion object {
        const val MaximumQueryCharacters = 512
    }
}
