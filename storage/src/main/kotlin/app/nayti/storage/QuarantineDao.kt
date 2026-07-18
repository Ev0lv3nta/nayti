package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

data class ExpiredQuarantineAsset(
    val assetId: Long,
    val quarantineStartedAtMillis: Long,
)

/** Finalizes deletion only after immutable vector snapshots containing the assets are gone. */
@Dao
interface QuarantineDao {
    @Query(
        "SELECT assetId, quarantineStartedAtMillis FROM catalog_asset " +
            "WHERE availability = 'OUT_OF_SCOPE' AND quarantineStartedAtMillis IS NOT NULL " +
            "AND quarantineStartedAtMillis <= :cutoffMillis AND derivedDataPurgedAtMillis IS NULL " +
            "ORDER BY quarantineStartedAtMillis, assetId LIMIT :limit",
    )
    suspend fun expiredAssets(cutoffMillis: Long, limit: Int): List<ExpiredQuarantineAsset>

    @Query("SELECT COUNT(*) FROM vector_segment_record WHERE assetId IN (:assetIds)")
    suspend fun vectorRecordCount(assetIds: List<Long>): Long

    @Query("DELETE FROM ocr_semantic_chunk_line WHERE assetId IN (:assetIds)")
    suspend fun deleteSemanticChunkLines(assetIds: List<Long>): Int

    @Query("DELETE FROM ocr_semantic_chunk WHERE assetId IN (:assetIds)")
    suspend fun deleteSemanticChunks(assetIds: List<Long>): Int

    @Query("DELETE FROM ocr_semantic_chunk_set WHERE assetId IN (:assetIds)")
    suspend fun deleteSemanticChunkSets(assetIds: List<Long>): Int

    @Query(
        "DELETE FROM ocr_lexical_fts WHERE rowid IN " +
            "(SELECT publicationEpoch FROM ocr_document WHERE assetId IN (:assetIds))",
    )
    suspend fun deleteLexicalRows(assetIds: List<Long>): Int

    @Query(
        "DELETE FROM ocr_trigram_fts WHERE rowid IN " +
            "(SELECT publicationEpoch FROM ocr_document WHERE assetId IN (:assetIds))",
    )
    suspend fun deleteTrigramRows(assetIds: List<Long>): Int

    @Query("DELETE FROM ocr_region WHERE assetId IN (:assetIds)")
    suspend fun deleteOcrRegions(assetIds: List<Long>): Int

    @Query("DELETE FROM ocr_document WHERE assetId IN (:assetIds)")
    suspend fun deleteOcrDocuments(assetIds: List<Long>): Int

    @Query("DELETE FROM perceptual_hash_result WHERE assetId IN (:assetIds)")
    suspend fun deletePerceptualHashes(assetIds: List<Long>): Int

    @Query("DELETE FROM index_channel_publication WHERE assetId IN (:assetIds)")
    suspend fun deleteChannelPublications(assetIds: List<Long>): Int

    @Query("DELETE FROM index_channel_work WHERE assetId IN (:assetIds)")
    suspend fun deleteChannelWork(assetIds: List<Long>): Int

    @Query("DELETE FROM index_error_ledger WHERE assetId IN (:assetIds)")
    suspend fun deleteErrorLedger(assetIds: List<Long>): Int

    @Query("DELETE FROM index_operation_asset WHERE assetId IN (:assetIds)")
    suspend fun deleteOperationAssets(assetIds: List<Long>): Int

    @Query(
        "UPDATE catalog_asset SET derivedDataPurgedAtMillis = :nowMillis " +
            "WHERE assetId IN (:assetIds) AND availability = 'OUT_OF_SCOPE' " +
            "AND quarantineStartedAtMillis IS NOT NULL AND quarantineStartedAtMillis <= :cutoffMillis " +
            "AND derivedDataPurgedAtMillis IS NULL",
    )
    suspend fun markPurged(assetIds: List<Long>, cutoffMillis: Long, nowMillis: Long): Int

    @Transaction
    suspend fun finalizePurge(
        assetIds: List<Long>,
        cutoffMillis: Long,
        nowMillis: Long,
    ): Int {
        require(assetIds.isNotEmpty() && assetIds.size <= MaximumBatchSize)
        require(assetIds.all { it > 0 } && assetIds.distinct().size == assetIds.size)
        require(cutoffMillis in 0..nowMillis)
        check(vectorRecordCount(assetIds) == 0L)
        deleteSemanticChunkLines(assetIds)
        deleteSemanticChunks(assetIds)
        deleteSemanticChunkSets(assetIds)
        deleteLexicalRows(assetIds)
        deleteTrigramRows(assetIds)
        deleteOcrRegions(assetIds)
        deleteOcrDocuments(assetIds)
        deletePerceptualHashes(assetIds)
        deleteChannelPublications(assetIds)
        deleteChannelWork(assetIds)
        deleteErrorLedger(assetIds)
        deleteOperationAssets(assetIds)
        return markPurged(assetIds, cutoffMillis, nowMillis)
    }

    companion object {
        const val MaximumBatchSize = 256
    }
}
