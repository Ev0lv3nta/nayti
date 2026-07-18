package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/** Removes rebuildable search data while preserving the media catalog and installed model packs. */
@Dao
interface SearchDataResetDao {
    @Query("DELETE FROM query_snapshot_lease")
    suspend fun deleteQueryLeases()

    @Query("DELETE FROM active_snapshot_pointer")
    suspend fun deleteActiveSnapshotPointer()

    @Query("DELETE FROM activation_candidate_channel")
    suspend fun deleteCandidateChannels()

    @Query("DELETE FROM activation_candidate")
    suspend fun deleteCandidates()

    @Query("DELETE FROM activation_snapshot_channel")
    suspend fun deleteSnapshotChannels()

    @Query("DELETE FROM vector_publication")
    suspend fun deleteVectorPublications()

    @Query("DELETE FROM vector_manifest_segment")
    suspend fun deleteManifestSegments()

    @Query("DELETE FROM vector_segment_record")
    suspend fun deleteSegmentRecords()

    @Query("DELETE FROM artifact_delete_intent")
    suspend fun deleteArtifactIntents()

    @Query("DELETE FROM activation_snapshot")
    suspend fun deleteSnapshots()

    @Query("DELETE FROM vector_manifest")
    suspend fun deleteManifests()

    @Query("DELETE FROM vector_segment_artifact")
    suspend fun deleteSegmentArtifacts()

    @Query("DELETE FROM vector_generation")
    suspend fun deleteVectorGenerations()

    @Query("DELETE FROM ocr_semantic_chunk_line")
    suspend fun deleteSemanticChunkLines()

    @Query("DELETE FROM ocr_semantic_chunk")
    suspend fun deleteSemanticChunks()

    @Query("DELETE FROM ocr_semantic_chunk_set")
    suspend fun deleteSemanticChunkSets()

    @Query("DELETE FROM ocr_lexical_fts")
    suspend fun deleteLexicalIndex()

    @Query("DELETE FROM ocr_trigram_fts")
    suspend fun deleteTrigramIndex()

    @Query("DELETE FROM ocr_region")
    suspend fun deleteOcrRegions()

    @Query("DELETE FROM ocr_document")
    suspend fun deleteOcrDocuments()

    @Query("DELETE FROM perceptual_hash_result")
    suspend fun deletePerceptualHashes()

    @Query("DELETE FROM index_channel_publication")
    suspend fun deleteChannelPublications()

    @Query("DELETE FROM index_channel_work")
    suspend fun deleteChannelWork()

    @Query("DELETE FROM index_error_ledger")
    suspend fun deleteErrorLedger()

    @Query("DELETE FROM index_execution_window")
    suspend fun deleteExecutionWindows()

    @Query("DELETE FROM index_operation_asset")
    suspend fun deleteOperationAssets()

    @Query("DELETE FROM index_operation_channel")
    suspend fun deleteOperationChannels()

    @Query("DELETE FROM index_operation")
    suspend fun deleteOperations()

    @Query("DELETE FROM index_publication_clock")
    suspend fun deletePublicationClock()

    @Query("SELECT COUNT(*) FROM index_channel_work")
    suspend fun channelWorkCount(): Long

    @Query("SELECT COUNT(*) FROM perceptual_hash_result")
    suspend fun perceptualHashCount(): Long

    @Transaction
    suspend fun reset() {
        deleteQueryLeases()
        deleteActiveSnapshotPointer()
        deleteCandidateChannels()
        deleteCandidates()
        deleteSnapshotChannels()
        deleteVectorPublications()
        deleteManifestSegments()
        deleteSegmentRecords()
        deleteArtifactIntents()
        deleteSnapshots()
        deleteManifests()
        deleteSegmentArtifacts()
        deleteVectorGenerations()
        deleteSemanticChunkLines()
        deleteSemanticChunks()
        deleteSemanticChunkSets()
        deleteLexicalIndex()
        deleteTrigramIndex()
        deleteOcrRegions()
        deleteOcrDocuments()
        deletePerceptualHashes()
        deleteChannelPublications()
        deleteChannelWork()
        deleteErrorLedger()
        deleteExecutionWindows()
        deleteOperationAssets()
        deleteOperationChannels()
        deleteOperations()
        deletePublicationClock()
    }
}
