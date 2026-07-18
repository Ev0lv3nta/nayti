package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
interface VectorIndexDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGenerationRow(generation: VectorGenerationEntity)

    @Query("SELECT * FROM vector_generation WHERE generationId = :generationId")
    suspend fun generation(generationId: String): VectorGenerationEntity?

    @Query(
        "UPDATE vector_generation SET state = 'SEALED', sealedAtMillis = :nowMillis " +
            "WHERE generationId = :generationId AND state = 'BUILDING'",
    )
    suspend fun sealGenerationRow(generationId: String, nowMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPublicationRow(publication: VectorPublicationEntity)

    @Query("SELECT * FROM vector_publication WHERE publicationToken = :publicationToken")
    suspend fun publication(publicationToken: String): VectorPublicationEntity?

    @Query(
        "UPDATE index_channel_work SET state = 'STAGED', publicationToken = :publicationToken, " +
            "stagedArtifactPath = :artifactPath, stagedArtifactLength = :artifactLength, " +
            "stagedArtifactSha256 = :artifactSha256, updatedAtMillis = :nowMillis " +
            "WHERE leaseToken = :leaseToken AND state = 'RUNNING' " +
            "AND leaseExpiresAtMillis > :nowMillis AND channel = :channel",
    )
    suspend fun stageWorkRow(
        leaseToken: String,
        publicationToken: String,
        channel: String,
        artifactPath: String,
        artifactLength: Long,
        artifactSha256: String,
        nowMillis: Long,
    ): Int

    @Query("SELECT * FROM index_channel_work WHERE publicationToken = :publicationToken ORDER BY assetId")
    suspend fun publicationWork(publicationToken: String): List<IndexChannelWorkEntity>

    @Query("SELECT * FROM index_channel_work WHERE leaseToken = :leaseToken")
    suspend fun workByLease(leaseToken: String): IndexChannelWorkEntity?

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun catalogAsset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun accessObservation(): CatalogAccessObservationEntity?

    @Query("SELECT * FROM index_publication_clock WHERE singletonId = 1")
    suspend fun publicationClock(): IndexPublicationClockEntity?

    @Query("SELECT COUNT(*) FROM catalog_asset WHERE availability = 'AVAILABLE'")
    suspend fun availableAssetCount(): Int

    @Query(
        "SELECT COUNT(*) FROM catalog_asset AS asset " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE asset.availability = 'AVAILABLE' AND access.accessScope != 'None' " +
            "AND EXISTS (SELECT 1 FROM ocr_document AS document " +
            "WHERE document.assetId = asset.assetId " +
            "AND document.sourceFingerprint = asset.sourceFingerprint " +
            "AND document.accessRevision = access.processAccessRevision " +
            "AND document.pipelineVersion = :pipelineVersion " +
            "AND document.componentHash = :componentHash " +
            "AND document.publicationEpoch <= :maximumPublicationEpoch)",
    )
    suspend fun coveredOcrAssetCount(
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
    ): Int

    @Query("SELECT * FROM model_pack WHERE packId = :packId AND packVersion = :packVersion")
    suspend fun modelPack(packId: String, packVersion: String): ModelPackEntity?

    @Query("SELECT * FROM ocr_semantic_chunk WHERE chunkId = :chunkId")
    suspend fun semanticChunk(chunkId: String): OcrSemanticChunkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegmentIfAbsent(segment: VectorSegmentArtifactEntity): Long

    @Query("SELECT * FROM vector_segment_artifact WHERE sha256 = :sha256")
    suspend fun segment(sha256: String): VectorSegmentArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegmentRecordsIfAbsent(records: List<VectorSegmentRecordEntity>): List<Long>

    @Query("SELECT * FROM vector_segment_record WHERE segmentSha256 = :segmentSha256 ORDER BY ordinal")
    suspend fun segmentRecords(segmentSha256: String): List<VectorSegmentRecordEntity>

    @Query(
        "SELECT vectorRecord.segmentSha256 AS segmentSha256, vectorRecord.recordId AS recordId, " +
            "vectorRecord.assetId AS assetId, chunk.chunkId AS chunkId, chunk.ordinal AS chunkOrdinal, " +
            "chunk.displayText AS displayText, chunk.meanConfidenceMicros AS meanConfidenceMicros, " +
            "chunk.firstLineOrdinal AS firstLineOrdinal, chunk.lastLineOrdinal AS lastLineOrdinal, " +
            "document.publicationEpoch AS publicationEpoch " +
            "FROM vector_segment_record AS vectorRecord " +
            "INNER JOIN vector_manifest_segment AS manifestSegment " +
            "ON manifestSegment.segmentSha256 = vectorRecord.segmentSha256 " +
            "INNER JOIN vector_manifest AS vectorManifest ON vectorManifest.revision = manifestSegment.manifestRevision " +
            "INNER JOIN vector_generation AS generation ON generation.generationId = vectorManifest.generationId " +
            "INNER JOIN ocr_semantic_chunk AS chunk ON chunk.chunkId = vectorRecord.semanticChunkId " +
            "INNER JOIN ocr_document AS document ON document.publicationToken = chunk.ocrPublicationToken " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = chunk.assetId " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE manifestSegment.manifestRevision = :manifestRevision " +
            "AND vectorRecord.recordId IN (:recordIds) " +
            "AND vectorRecord.assetId = chunk.assetId " +
            "AND document.assetId = chunk.assetId " +
            "AND vectorRecord.sourceFingerprint = chunk.sourceFingerprint " +
            "AND vectorRecord.accessRevision = access.processAccessRevision " +
            "AND vectorRecord.chunkOrdinal = chunk.ordinal " +
            "AND chunk.ocrPublicationToken = document.publicationToken " +
            "AND chunk.sourceFingerprint = document.sourceFingerprint " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND asset.sourceFingerprint = document.sourceFingerprint " +
            "AND generation.pipelineVersion = :semanticPipelineVersion " +
            "AND generation.componentHash = :componentHash " +
            "AND document.accessRevision = access.processAccessRevision " +
            "AND document.pipelineVersion = :ocrPipelineVersion " +
            "AND document.componentHash = :ocrComponentHash " +
            "AND document.publicationEpoch <= :maximumPublicationEpoch " +
            "AND access.accessScope != 'None' " +
            "ORDER BY vectorRecord.recordId, vectorRecord.segmentSha256",
    )
    suspend fun semanticEvidenceRows(
        manifestRevision: String,
        recordIds: List<Long>,
        semanticPipelineVersion: String,
        componentHash: String,
        ocrPipelineVersion: String,
        ocrComponentHash: String,
        maximumPublicationEpoch: Long,
    ): List<SemanticVectorEvidence>

    @Query(
        "SELECT vectorRecord.recordId FROM vector_segment_record AS vectorRecord " +
            "INNER JOIN vector_manifest_segment AS manifestSegment " +
            "ON manifestSegment.segmentSha256 = vectorRecord.segmentSha256 " +
            "INNER JOIN vector_manifest AS vectorManifest ON vectorManifest.revision = manifestSegment.manifestRevision " +
            "INNER JOIN vector_generation AS generation ON generation.generationId = vectorManifest.generationId " +
            "INNER JOIN ocr_semantic_chunk AS chunk ON chunk.chunkId = vectorRecord.semanticChunkId " +
            "INNER JOIN ocr_document AS document ON document.publicationToken = chunk.ocrPublicationToken " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = chunk.assetId " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE manifestSegment.manifestRevision = :manifestRevision " +
            "AND vectorRecord.segmentSha256 = :segmentSha256 " +
            "AND vectorRecord.assetId = chunk.assetId " +
            "AND document.assetId = chunk.assetId " +
            "AND vectorRecord.sourceFingerprint = chunk.sourceFingerprint " +
            "AND vectorRecord.accessRevision = access.processAccessRevision " +
            "AND vectorRecord.chunkOrdinal = chunk.ordinal " +
            "AND chunk.ocrPublicationToken = document.publicationToken " +
            "AND chunk.sourceFingerprint = document.sourceFingerprint " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND (:takenFromMillis IS NULL OR COALESCE(asset.dateTakenMillis, " +
            "asset.dateModifiedSeconds * 1000) >= :takenFromMillis) " +
            "AND (:takenBeforeMillis IS NULL OR COALESCE(asset.dateTakenMillis, " +
            "asset.dateModifiedSeconds * 1000) < :takenBeforeMillis) " +
            "AND (:bucketId IS NULL OR asset.bucketId = :bucketId) " +
            "AND (:mimeType IS NULL OR asset.mimeType = :mimeType) " +
            "AND asset.sourceFingerprint = document.sourceFingerprint " +
            "AND generation.pipelineVersion = :semanticPipelineVersion " +
            "AND generation.componentHash = :componentHash " +
            "AND document.accessRevision = access.processAccessRevision " +
            "AND document.pipelineVersion = :ocrPipelineVersion " +
            "AND document.componentHash = :ocrComponentHash " +
            "AND document.publicationEpoch <= :maximumPublicationEpoch " +
            "AND access.accessScope != 'None' " +
            "ORDER BY vectorRecord.recordId",
    )
    suspend fun semanticEligibleRecordIds(
        manifestRevision: String,
        segmentSha256: String,
        semanticPipelineVersion: String,
        componentHash: String,
        ocrPipelineVersion: String,
        ocrComponentHash: String,
        maximumPublicationEpoch: Long,
        takenFromMillis: Long?,
        takenBeforeMillis: Long?,
        bucketId: Long?,
        mimeType: String?,
    ): List<Long>

    @Query(
        "SELECT vectorRecord.segmentSha256 AS segmentSha256, " +
            "vectorRecord.recordId AS recordId, vectorRecord.assetId AS assetId, " +
            "vectorRecord.sourceFingerprint AS sourceFingerprint " +
            "FROM vector_segment_record AS vectorRecord " +
            "INNER JOIN vector_manifest_segment AS manifestSegment " +
            "ON manifestSegment.segmentSha256 = vectorRecord.segmentSha256 " +
            "INNER JOIN vector_manifest AS vectorManifest ON vectorManifest.revision = manifestSegment.manifestRevision " +
            "INNER JOIN vector_generation AS generation ON generation.generationId = vectorManifest.generationId " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = vectorRecord.assetId " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE manifestSegment.manifestRevision = :manifestRevision " +
            "AND vectorRecord.recordId IN (:recordIds) " +
            "AND vectorRecord.recordId = vectorRecord.assetId " +
            "AND vectorRecord.chunkOrdinal = 0 AND vectorRecord.semanticChunkId IS NULL " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND asset.sourceFingerprint = vectorRecord.sourceFingerprint " +
            "AND vectorRecord.accessRevision = access.processAccessRevision " +
            "AND generation.pipelineVersion = :visualPipelineVersion " +
            "AND generation.componentHash = :componentHash " +
            "AND access.accessScope != 'None' " +
            "ORDER BY vectorRecord.recordId, vectorRecord.segmentSha256",
    )
    suspend fun visualEvidenceRows(
        manifestRevision: String,
        recordIds: List<Long>,
        visualPipelineVersion: String,
        componentHash: String,
    ): List<VisualVectorEvidence>

    @Query(
        "SELECT vectorRecord.recordId FROM vector_segment_record AS vectorRecord " +
            "INNER JOIN vector_manifest_segment AS manifestSegment " +
            "ON manifestSegment.segmentSha256 = vectorRecord.segmentSha256 " +
            "INNER JOIN vector_manifest AS vectorManifest ON vectorManifest.revision = manifestSegment.manifestRevision " +
            "INNER JOIN vector_generation AS generation ON generation.generationId = vectorManifest.generationId " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = vectorRecord.assetId " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE manifestSegment.manifestRevision = :manifestRevision " +
            "AND vectorRecord.segmentSha256 = :segmentSha256 " +
            "AND vectorRecord.recordId = vectorRecord.assetId " +
            "AND vectorRecord.chunkOrdinal = 0 AND vectorRecord.semanticChunkId IS NULL " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND (:takenFromMillis IS NULL OR COALESCE(asset.dateTakenMillis, " +
            "asset.dateModifiedSeconds * 1000) >= :takenFromMillis) " +
            "AND (:takenBeforeMillis IS NULL OR COALESCE(asset.dateTakenMillis, " +
            "asset.dateModifiedSeconds * 1000) < :takenBeforeMillis) " +
            "AND (:bucketId IS NULL OR asset.bucketId = :bucketId) " +
            "AND (:mimeType IS NULL OR asset.mimeType = :mimeType) " +
            "AND asset.sourceFingerprint = vectorRecord.sourceFingerprint " +
            "AND vectorRecord.accessRevision = access.processAccessRevision " +
            "AND generation.pipelineVersion = :visualPipelineVersion " +
            "AND generation.componentHash = :componentHash " +
            "AND access.accessScope != 'None' " +
            "ORDER BY vectorRecord.recordId",
    )
    suspend fun visualEligibleRecordIds(
        manifestRevision: String,
        segmentSha256: String,
        visualPipelineVersion: String,
        componentHash: String,
        takenFromMillis: Long?,
        takenBeforeMillis: Long?,
        bucketId: Long?,
        mimeType: String?,
    ): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManifest(manifest: VectorManifestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManifestSegments(segments: List<VectorManifestSegmentEntity>)

    @Query("SELECT * FROM vector_manifest WHERE revision = :revision")
    suspend fun manifest(revision: String): VectorManifestEntity?

    @Query("SELECT * FROM vector_manifest ORDER BY createdAtMillis, revision")
    suspend fun manifests(): List<VectorManifestEntity>

    @Query("SELECT * FROM vector_manifest_segment WHERE manifestRevision = :revision ORDER BY ordinal")
    suspend fun manifestSegments(revision: String): List<VectorManifestSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: ActivationSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshotChannels(channels: List<ActivationSnapshotChannelEntity>)

    @Query("SELECT * FROM activation_snapshot WHERE snapshotId = :snapshotId")
    suspend fun snapshot(snapshotId: String): ActivationSnapshotEntity?

    @Query("SELECT * FROM activation_snapshot_channel WHERE snapshotId = :snapshotId ORDER BY channel")
    suspend fun snapshotChannels(snapshotId: String): List<ActivationSnapshotChannelEntity>

    @Query(
        "SELECT * FROM activation_snapshot_channel WHERE snapshotId = :snapshotId AND channel = :channel",
    )
    suspend fun snapshotChannel(snapshotId: String, channel: String): ActivationSnapshotChannelEntity?

    @Query("SELECT * FROM activation_snapshot ORDER BY createdAtMillis, snapshotId")
    suspend fun snapshots(): List<ActivationSnapshotEntity>

    @Query("SELECT * FROM vector_segment_artifact ORDER BY createdAtMillis, sha256")
    suspend fun segments(): List<VectorSegmentArtifactEntity>

    @Query("SELECT snapshotId FROM active_snapshot_pointer WHERE singletonId = 1")
    suspend fun activeSnapshotId(): String?

    @Query("SELECT * FROM active_snapshot_pointer WHERE singletonId = 1")
    suspend fun activePointer(): ActiveSnapshotPointerEntity?

    @Upsert
    suspend fun replaceActivePointer(pointer: ActiveSnapshotPointerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActivationCandidate(candidate: ActivationCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActivationCandidateChannels(channels: List<ActivationCandidateChannelEntity>)

    @Query("SELECT * FROM activation_candidate WHERE candidateId = :candidateId")
    suspend fun activationCandidate(candidateId: String): ActivationCandidateEntity?

    @Query("SELECT * FROM activation_candidate WHERE snapshotId = :snapshotId")
    suspend fun activationCandidateBySnapshot(snapshotId: String): ActivationCandidateEntity?

    @Query("SELECT * FROM activation_candidate ORDER BY createdAtMillis, candidateId")
    suspend fun activationCandidates(): List<ActivationCandidateEntity>

    @Query("SELECT * FROM activation_candidate_channel WHERE candidateId = :candidateId ORDER BY channel")
    suspend fun activationCandidateChannels(candidateId: String): List<ActivationCandidateChannelEntity>

    @Query(
        "SELECT COUNT(*) FROM activation_candidate WHERE snapshotId = :snapshotId " +
            "AND state IN ('BUILDING_SHADOW', 'READY_TO_ACTIVATE', 'ACTIVE')",
    )
    suspend fun activationRootCount(snapshotId: String): Int

    @Query(
        "SELECT COUNT(*) FROM activation_candidate " +
            "WHERE state IN ('BUILDING_SHADOW', 'READY_TO_ACTIVATE')",
    )
    suspend fun unfinishedActivationCandidateCount(): Int

    @Query(
        "UPDATE activation_candidate SET state = :newState, updatedAtMillis = :nowMillis, " +
            "failureCode = :failureCode WHERE candidateId = :candidateId AND state = :expectedState",
    )
    suspend fun transitionActivationCandidateRow(
        candidateId: String,
        expectedState: String,
        newState: String,
        nowMillis: Long,
        failureCode: String?,
    ): Int

    @Query(
        "UPDATE activation_candidate SET capturedCatalogWatermark = :nextWatermark, " +
            "updatedAtMillis = :nowMillis WHERE candidateId = :candidateId " +
            "AND state = 'BUILDING_SHADOW' AND capturedCatalogWatermark = :expectedWatermark",
    )
    suspend fun advanceActivationCandidateWatermarkRow(
        candidateId: String,
        expectedWatermark: Long,
        nextWatermark: Long,
        nowMillis: Long,
    ): Int

    @Query("SELECT * FROM catalog_watermark WHERE singletonId = 1")
    suspend fun catalogWatermark(): CatalogWatermarkEntity?

    @Query(
        "UPDATE index_channel_work SET state = 'DONE', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, stagedArtifactPath = NULL, stagedArtifactLength = NULL, " +
            "stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, errorCode = NULL, " +
            "updatedAtMillis = :nowMillis WHERE publicationToken = :publicationToken AND state = 'STAGED'",
    )
    suspend fun completePublicationWork(publicationToken: String, nowMillis: Long): Int

    @Query(
        "UPDATE vector_publication SET state = 'DONE', updatedAtMillis = :nowMillis " +
            "WHERE publicationToken = :publicationToken AND state = 'STAGED'",
    )
    suspend fun completePublicationRow(publicationToken: String, nowMillis: Long): Int

    @Query(
        "UPDATE index_channel_work SET state = 'PENDING', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, publicationToken = NULL, stagedArtifactPath = NULL, " +
            "stagedArtifactLength = NULL, stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, " +
            "errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE publicationToken = :publicationToken AND state = 'STAGED'",
    )
    suspend fun resetStagedPublicationWork(publicationToken: String, nowMillis: Long): Int

    @Query(
        "UPDATE vector_publication SET state = 'ABANDONED', updatedAtMillis = :nowMillis " +
            "WHERE publicationToken = :publicationToken AND state = 'STAGED'",
    )
    suspend fun abandonPublicationRow(publicationToken: String, nowMillis: Long): Int

    @Query("SELECT * FROM vector_publication WHERE state = 'STAGED' ORDER BY createdAtMillis")
    suspend fun stagedPublications(): List<VectorPublicationEntity>

    @Query(
        "SELECT publication.* FROM vector_publication AS publication " +
            "INNER JOIN vector_manifest AS manifest ON manifest.revision = publication.manifestRevision " +
            "WHERE publication.snapshotId = :snapshotId AND publication.channel = :channel " +
            "AND publication.state = 'DONE' " +
            "ORDER BY manifest.segmentCount DESC, publication.createdAtMillis DESC, publication.publicationToken DESC " +
            "LIMIT 1",
    )
    suspend fun latestCompletedPublication(
        snapshotId: String,
        channel: String,
    ): VectorPublicationEntity?

    @Upsert
    suspend fun replaceQueryLease(lease: QuerySnapshotLeaseEntity)

    @Query("SELECT * FROM query_snapshot_lease WHERE leaseToken = :leaseToken")
    suspend fun queryLease(leaseToken: String): QuerySnapshotLeaseEntity?

    @Query("DELETE FROM query_snapshot_lease WHERE leaseToken = :leaseToken")
    suspend fun releaseQueryLease(leaseToken: String): Int

    @Query(
        "UPDATE query_snapshot_lease SET expiresAtMillis = :expiresAtMillis " +
            "WHERE leaseToken = :leaseToken AND expiresAtMillis > :nowMillis",
    )
    suspend fun renewQueryLeaseRow(leaseToken: String, nowMillis: Long, expiresAtMillis: Long): Int

    @Query("DELETE FROM query_snapshot_lease WHERE expiresAtMillis <= :nowMillis")
    suspend fun expireQueryLeases(nowMillis: Long): Int

    @Query(
        "SELECT COUNT(*) FROM query_snapshot_lease " +
            "WHERE snapshotId = :snapshotId AND expiresAtMillis > :nowMillis",
    )
    suspend fun liveQueryLeaseCount(snapshotId: String, nowMillis: Long): Int

    @Query("SELECT * FROM artifact_delete_intent WHERE ownerSnapshotId = :snapshotId ORDER BY relativePath")
    suspend fun deleteIntents(snapshotId: String): List<ArtifactDeleteIntentEntity>

    @Query("SELECT COUNT(*) FROM artifact_delete_intent WHERE ownerSnapshotId = :snapshotId")
    suspend fun deleteIntentCount(snapshotId: String): Int

    @Query("SELECT DISTINCT ownerSnapshotId FROM artifact_delete_intent ORDER BY ownerSnapshotId")
    suspend fun deleteIntentOwners(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDeleteIntents(intents: List<ArtifactDeleteIntentEntity>)

    @Query(
        "UPDATE artifact_delete_intent SET state = 'CONFIRMED' " +
            "WHERE relativePath = :relativePath AND state = 'PENDING'",
    )
    suspend fun confirmDeleteIntent(relativePath: String): Int

    @Query("DELETE FROM artifact_delete_intent WHERE ownerSnapshotId = :snapshotId")
    suspend fun deleteDeleteIntents(snapshotId: String): Int

    @Query(
        "SELECT COUNT(*) FROM activation_snapshot WHERE snapshotId != :excludingSnapshotId " +
            "AND (semanticManifestRevision = :revision OR visualManifestRevision = :revision)",
    )
    suspend fun otherSnapshotManifestReferenceCount(revision: String, excludingSnapshotId: String): Int

    @Query(
        "SELECT COUNT(*) FROM vector_manifest_segment " +
            "WHERE segmentSha256 = :segmentSha256 AND manifestRevision != :excludingRevision",
    )
    suspend fun otherManifestSegmentReferenceCount(segmentSha256: String, excludingRevision: String): Int

    @Query("DELETE FROM vector_manifest_segment WHERE manifestRevision = :revision")
    suspend fun deleteManifestSegments(revision: String): Int

    @Query("DELETE FROM vector_manifest WHERE revision = :revision")
    suspend fun deleteManifest(revision: String): Int

    @Query("SELECT COUNT(*) FROM vector_manifest_segment WHERE segmentSha256 = :segmentSha256")
    suspend fun segmentReferenceCount(segmentSha256: String): Int

    @Query("DELETE FROM vector_segment_record WHERE segmentSha256 = :segmentSha256")
    suspend fun deleteSegmentRecords(segmentSha256: String): Int

    @Query("DELETE FROM vector_segment_artifact WHERE sha256 = :sha256")
    suspend fun deleteSegment(sha256: String): Int

    @Query("DELETE FROM activation_snapshot WHERE snapshotId = :snapshotId")
    suspend fun deleteSnapshot(snapshotId: String): Int

    @Query("DELETE FROM activation_snapshot_channel WHERE snapshotId = :snapshotId")
    suspend fun deleteSnapshotChannels(snapshotId: String): Int

    @Transaction
    suspend fun createGeneration(generation: VectorGenerationEntity) {
        require(identifier(generation.generationId))
        require(generation.channel in VectorChannels)
        require(identifier(generation.packId))
        require(contractValue(generation.packVersion))
        require(contractValue(generation.pipelineVersion))
        require(sha256(generation.componentHash))
        require(sha256(generation.embeddingSpaceHash))
        require(generation.dimension in 1..MaximumVectorDimension)
        require(generation.state == VectorGenerationState.BUILDING)
        require(generation.createdAtMillis >= 0 && generation.sealedAtMillis == null)
        check(modelPack(generation.packId, generation.packVersion) != null)
        insertGenerationRow(generation)
    }

    @Transaction
    suspend fun stageVectorPublication(
        publication: VectorPublicationEntity,
        leaseTokens: List<String>,
        artifactPath: String,
        artifactLength: Long,
        artifactSha256: String,
        nowMillis: Long,
    ): Int {
        require(identifier(publication.publicationToken))
        require(publication.state == VectorPublicationState.STAGED)
        require(publication.channel in VectorChannels)
        require(identifier(publication.generationId))
        require(sha256(publication.segmentSha256) && publication.segmentSha256 == artifactSha256)
        require(identifier(publication.manifestRevision))
        require(identifier(publication.snapshotId))
        require(publication.createdAtMillis == nowMillis && publication.updatedAtMillis == nowMillis)
        require(leaseTokens.isNotEmpty() && leaseTokens.size <= MaximumSegmentRecords)
        require(leaseTokens.distinct().size == leaseTokens.size)
        require(relativeArtifactPath(artifactPath))
        require(artifactLength > 0)
        require(sha256(artifactSha256))
        val generation = checkNotNull(generation(publication.generationId))
        check(generation.state == VectorGenerationState.BUILDING && generation.channel == publication.channel)
        val work = leaseTokens.map { leaseToken -> workByLease(leaseToken) ?: return 0 }
        if (
            work.any { item ->
                item.state != IndexWorkState.RUNNING ||
                    item.channel != publication.channel ||
                    item.pipelineVersion != generation.pipelineVersion ||
                    item.componentHash != generation.componentHash ||
                    (item.leaseExpiresAtMillis ?: 0) <= nowMillis
            }
        ) {
            return 0
        }
        insertPublicationRow(publication)
        leaseTokens.forEach { leaseToken ->
            check(
                stageWorkRow(
                    leaseToken = leaseToken,
                    publicationToken = publication.publicationToken,
                    channel = publication.channel,
                    artifactPath = artifactPath,
                    artifactLength = artifactLength,
                    artifactSha256 = artifactSha256,
                    nowMillis = nowMillis,
                ) == 1,
            )
        }
        return leaseTokens.size
    }

    @Transaction
    suspend fun commitVectorPublication(
        publicationToken: String,
        segment: VectorSegmentArtifactEntity,
        records: List<VectorSegmentRecordEntity>,
        manifest: VectorManifestEntity,
        manifestEntries: List<VectorManifestSegmentEntity>,
        snapshot: ActivationSnapshotEntity,
        nowMillis: Long,
    ): ActivationSnapshotEntity {
        val publication = checkNotNull(publication(publicationToken))
        check(publication.state == VectorPublicationState.STAGED)
        check(publication.segmentSha256 == segment.sha256)
        check(publication.manifestRevision == manifest.revision)
        check(publication.snapshotId == snapshot.snapshotId)
        val generation = checkNotNull(generation(publication.generationId))
        validateSegment(generation, segment, records)
        validateSemanticRecordReferences(generation, records)

        val work = publicationWork(publicationToken)
        check(work.isNotEmpty() && work.all { item ->
            item.state == IndexWorkState.STAGED &&
                item.channel == publication.channel &&
                item.pipelineVersion == generation.pipelineVersion &&
                item.componentHash == generation.componentHash &&
                item.stagedArtifactPath == segment.relativePath &&
                item.stagedArtifactLength == segment.byteLength &&
                item.stagedArtifactSha256 == segment.sha256
        })
        val recordAssetIds = records.map(VectorSegmentRecordEntity::assetId).toSet()
        check(work.map(IndexChannelWorkEntity::assetId).toSet() == recordAssetIds)
        val access = checkNotNull(accessObservation())
        check(access.accessScope != "None")
        work.forEach { item ->
            check(item.accessRevision == access.processAccessRevision)
            val asset = checkNotNull(catalogAsset(item.assetId))
            check(asset.availability == CatalogAvailability.AVAILABLE)
            check(asset.sourceFingerprint == item.sourceFingerprint)
            check(
                records.filter { it.assetId == item.assetId }.all {
                    it.sourceFingerprint == item.sourceFingerprint && it.accessRevision == item.accessRevision
                },
            )
        }

        insertSegmentIfAbsent(segment)
        check(segment(segment.sha256) == segment)
        if (records.isNotEmpty()) insertSegmentRecordsIfAbsent(records)
        check(segmentRecords(segment.sha256) == records.sortedBy(VectorSegmentRecordEntity::ordinal))
        validateManifest(generation, manifest, manifestEntries)
        check(manifestEntries.any { it.segmentSha256 == segment.sha256 })
        manifestEntries.forEach { entry ->
            val artifact = checkNotNull(segment(entry.segmentSha256))
            check(artifact.channel == generation.channel)
            check(artifact.embeddingSpaceHash == generation.embeddingSpaceHash)
            check(artifact.dimension == generation.dimension)
        }
        insertManifest(manifest)
        insertManifestSegments(manifestEntries)
        validateSnapshot(snapshot, generation, manifest)
        insertSnapshot(snapshot)
        insertSnapshotChannels(buildPublishedSnapshotChannels(snapshot, generation, manifest))
        advanceActivePointer(snapshot.snapshotId, nowMillis)
        check(completePublicationWork(publicationToken, nowMillis) == work.size)
        check(completePublicationRow(publicationToken, nowMillis) == 1)
        return snapshot
    }

    @Transaction
    suspend fun commitShadowVectorPublication(
        publicationToken: String,
        segment: VectorSegmentArtifactEntity,
        records: List<VectorSegmentRecordEntity>,
        manifest: VectorManifestEntity,
        manifestEntries: List<VectorManifestSegmentEntity>,
        nowMillis: Long,
    ): VectorManifestEntity {
        val publication = checkNotNull(publication(publicationToken))
        check(publication.state == VectorPublicationState.STAGED)
        check(publication.segmentSha256 == segment.sha256)
        check(publication.manifestRevision == manifest.revision)
        val candidate = checkNotNull(activationCandidateBySnapshot(publication.snapshotId))
        check(candidate.state == ActivationCandidateState.BUILDING_SHADOW)
        val generation = checkNotNull(generation(publication.generationId))
        check(generation.packId == candidate.packId && generation.packVersion == candidate.packVersion)
        val planned = activationCandidateChannels(candidate.candidateId).single { it.channel == generation.channel }
        check(planned.action == ActivationCandidateChannelAction.REBUILD_SHADOW)
        check(
            planned.pipelineVersion == generation.pipelineVersion &&
                planned.componentHash == generation.componentHash &&
                planned.embeddingSpaceHash == generation.embeddingSpaceHash,
        )
        validateSegment(generation, segment, records)
        validateSemanticRecordReferences(generation, records)

        val work = publicationWork(publicationToken)
        check(work.isNotEmpty() && work.all { item ->
            item.state == IndexWorkState.STAGED &&
                item.channel == publication.channel &&
                item.pipelineVersion == generation.pipelineVersion &&
                item.componentHash == generation.componentHash &&
                item.stagedArtifactPath == segment.relativePath &&
                item.stagedArtifactLength == segment.byteLength &&
                item.stagedArtifactSha256 == segment.sha256
        })
        check(work.map(IndexChannelWorkEntity::assetId).toSet() == records.map(VectorSegmentRecordEntity::assetId).toSet())
        val access = checkNotNull(accessObservation())
        check(
            access.accessScope != "None" &&
                access.processAccessRevision == candidate.capturedAccessRevision &&
                (catalogWatermark()?.catalogRevision ?: 0) >= candidate.capturedCatalogWatermark,
        )
        work.forEach { item ->
            check(item.accessRevision == access.processAccessRevision)
            val asset = checkNotNull(catalogAsset(item.assetId))
            check(asset.availability == CatalogAvailability.AVAILABLE)
            check(asset.sourceFingerprint == item.sourceFingerprint)
            check(
                records.filter { it.assetId == item.assetId }.all {
                    it.sourceFingerprint == item.sourceFingerprint && it.accessRevision == item.accessRevision
                },
            )
        }

        insertSegmentIfAbsent(segment)
        check(segment(segment.sha256) == segment)
        if (records.isNotEmpty()) insertSegmentRecordsIfAbsent(records)
        check(segmentRecords(segment.sha256) == records.sortedBy(VectorSegmentRecordEntity::ordinal))
        validateManifest(generation, manifest, manifestEntries)
        check(manifestEntries.any { it.segmentSha256 == segment.sha256 })
        manifestEntries.forEach { entry ->
            val artifact = checkNotNull(segment(entry.segmentSha256))
            check(artifact.channel == generation.channel)
            check(artifact.embeddingSpaceHash == generation.embeddingSpaceHash)
            check(artifact.dimension == generation.dimension)
        }
        insertManifest(manifest)
        insertManifestSegments(manifestEntries)
        check(completePublicationWork(publicationToken, nowMillis) == work.size)
        check(completePublicationRow(publicationToken, nowMillis) == 1)
        return manifest
    }

    @Transaction
    suspend fun commitVectorCompaction(
        generationId: String,
        segment: VectorSegmentArtifactEntity,
        records: List<VectorSegmentRecordEntity>,
        manifest: VectorManifestEntity,
        manifestEntries: List<VectorManifestSegmentEntity>,
        candidateSnapshot: ActivationSnapshotEntity,
        nowMillis: Long,
    ): ActivationSnapshotEntity {
        val generation = checkNotNull(generation(generationId))
        check(generation.state in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED))
        require(segment.createdAtMillis in 0..nowMillis)
        require(manifest.createdAtMillis in 0..nowMillis)
        require(candidateSnapshot.createdAtMillis in 0..nowMillis)
        validateSegment(generation, segment, records)
        validateSemanticRecordReferences(generation, records)
        val activeId = checkNotNull(activeSnapshotId())
        val active = checkNotNull(snapshot(activeId))
        val activeManifestRevision =
            if (generation.channel == IndexChannel.VISUAL) {
                active.visualManifestRevision
            } else {
                active.semanticManifestRevision
            }
        check(manifest.parentRevision == activeManifestRevision)
        val parentManifest = checkNotNull(activeManifestRevision?.let { manifest(it) })
        check(parentManifest.generationId == generationId)
        if (generation.channel == IndexChannel.VISUAL) {
            check(manifest.recordCount in 1..parentManifest.recordCount)
        } else {
            check(manifest.recordCount == parentManifest.recordCount)
        }

        insertSegmentIfAbsent(segment)
        check(segment(segment.sha256) == segment)
        insertSegmentRecordsIfAbsent(records)
        check(segmentRecords(segment.sha256) == records.sortedBy(VectorSegmentRecordEntity::ordinal))
        validateManifest(generation, manifest, manifestEntries)
        check(manifestEntries.any { it.segmentSha256 == segment.sha256 })
        insertManifest(manifest)
        insertManifestSegments(manifestEntries)
        validateSnapshot(candidateSnapshot, generation, manifest)
        insertSnapshot(candidateSnapshot)
        insertSnapshotChannels(buildPublishedSnapshotChannels(candidateSnapshot, generation, manifest))
        advanceActivePointer(candidateSnapshot.snapshotId, nowMillis)
        return candidateSnapshot
    }

    @Transaction
    suspend fun commitQuarantinePrune(
        expectedActiveSnapshotId: String,
        sanitizedSnapshot: ActivationSnapshotEntity,
        channels: List<ActivationSnapshotChannelEntity>,
        vectorChannels: List<QuarantineVectorChannelCommit>,
        nowMillis: Long,
    ): ActivationSnapshotEntity {
        require(
            identifier(sanitizedSnapshot.snapshotId) &&
                sanitizedSnapshot.snapshotId != expectedActiveSnapshotId &&
                sanitizedSnapshot.createdAtMillis in 0..nowMillis &&
                sanitizedSnapshot.formatVersion == ActivationSnapshotFormat.Current,
        )
        check(snapshot(sanitizedSnapshot.snapshotId) == null)
        check(activeSnapshotId() == expectedActiveSnapshotId)
        check(unfinishedActivationCandidateCount() == 0)
        val active = checkNotNull(snapshot(expectedActiveSnapshotId))
        require(
            sanitizedSnapshot.parentSnapshotId == null &&
                sanitizedSnapshot.packId == active.packId &&
                sanitizedSnapshot.packVersion == active.packVersion &&
                sanitizedSnapshot.packManifestSha256 == active.packManifestSha256 &&
                sanitizedSnapshot.engineContractVersion == active.engineContractVersion &&
                sanitizedSnapshot.rankingConfigVersion == active.rankingConfigVersion &&
                sanitizedSnapshot.lexicalPublicationEpoch == active.lexicalPublicationEpoch &&
                sanitizedSnapshot.pHashPublicationEpoch == active.pHashPublicationEpoch &&
                sanitizedSnapshot.catalogWatermark == active.catalogWatermark &&
                sanitizedSnapshot.capturedAccessRevision == active.capturedAccessRevision,
        )
        require(vectorChannels.map { it.manifest.channel }.distinct().size == vectorChannels.size)
        require(vectorChannels.all { it.manifest.channel in setOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL) })
        val activeChannels = snapshotChannels(expectedActiveSnapshotId).associateBy { it.channel }
        vectorChannels.forEach { channel ->
            val generation = checkNotNull(generation(channel.generationId))
            val source = checkNotNull(activeChannels[channel.manifest.channel])
            check(
                generation.generationId == channel.manifest.generationId &&
                    generation.channel == channel.manifest.channel &&
                    generation.packId == sanitizedSnapshot.packId &&
                    generation.packVersion == sanitizedSnapshot.packVersion &&
                    generation.state in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED) &&
                    source.pipelineVersion == generation.pipelineVersion &&
                    source.componentHash == generation.componentHash &&
                    source.embeddingSpaceHash == generation.embeddingSpaceHash &&
                    source.generationId == generation.generationId,
            )
            check(channel.manifest.parentRevision == null)
            val recordsBySegment = channel.records.groupBy(VectorSegmentRecordEntity::segmentSha256)
            channel.segments.forEach { segment ->
                val records = recordsBySegment[segment.sha256].orEmpty()
                validateSegment(generation, segment, records)
                validateSemanticRecordReferences(generation, records)
                insertSegmentIfAbsent(segment)
                check(segment(segment.sha256) == segment)
                insertSegmentRecordsIfAbsent(records)
                check(segmentRecords(segment.sha256) == records.sortedBy(VectorSegmentRecordEntity::ordinal))
            }
            check(recordsBySegment.keys == channel.segments.map { it.sha256 }.toSet())
            validateManifest(generation, channel.manifest, channel.manifestEntries)
            insertManifest(channel.manifest)
            insertManifestSegments(channel.manifestEntries)
        }
        check(
            sanitizedSnapshot.semanticManifestRevision ==
                vectorChannels.singleOrNull { it.manifest.channel == IndexChannel.OCR_SEMANTIC }?.manifest?.revision,
        )
        check(
            sanitizedSnapshot.visualManifestRevision ==
                vectorChannels.singleOrNull { it.manifest.channel == IndexChannel.VISUAL }?.manifest?.revision,
        )
        val expectedChannelNames =
            buildSet {
                add(IndexChannel.OCR)
                add(IndexChannel.PHASH)
                if (sanitizedSnapshot.semanticManifestRevision != null) add(IndexChannel.OCR_SEMANTIC)
                if (sanitizedSnapshot.visualManifestRevision != null) add(IndexChannel.VISUAL)
            }
        require(channels.map { it.channel }.distinct().size == channels.size)
        require(channels.map { it.channel }.toSet() == expectedChannelNames)
        require(channels.all { it.snapshotId == sanitizedSnapshot.snapshotId && it.inheritedFromSnapshotId == null })
        channels.filter { it.channel in setOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL) }.forEach { channel ->
            val committed = vectorChannels.single { it.manifest.channel == channel.channel }
            val generation = checkNotNull(generation(committed.generationId))
            check(
                channel.pipelineVersion == generation.pipelineVersion &&
                    channel.componentHash == generation.componentHash &&
                    channel.embeddingSpaceHash == generation.embeddingSpaceHash &&
                    channel.generationId == generation.generationId &&
                    channel.manifestRevision == committed.manifest.revision,
            )
        }
        channels.filter { it.channel in setOf(IndexChannel.OCR, IndexChannel.PHASH) }.forEach { channel ->
            val source = checkNotNull(activeChannels[channel.channel])
            check(
                channel.pipelineVersion == source.pipelineVersion &&
                    channel.componentHash == source.componentHash &&
                    channel.embeddingSpaceHash == null &&
                    channel.generationId == null &&
                    channel.manifestRevision == null,
            )
        }
        insertSnapshot(sanitizedSnapshot)
        insertSnapshotChannels(channels)
        activationCandidates()
            .filter { candidate -> candidate.state == ActivationCandidateState.ACTIVE }
            .forEach { candidate ->
                check(
                    transitionActivationCandidateRow(
                        candidateId = candidate.candidateId,
                        expectedState = ActivationCandidateState.ACTIVE,
                        newState = ActivationCandidateState.ROLLED_BACK,
                        nowMillis = nowMillis,
                        failureCode = "QUARANTINE_PURGE",
                    ) == 1,
                )
            }
        val pointer = activePointer()
        replaceActivePointer(
            ActiveSnapshotPointerEntity(
                snapshotId = sanitizedSnapshot.snapshotId,
                rollbackSnapshotId = null,
                activationSequence = Math.addExact(pointer?.activationSequence ?: 0, 1),
                updatedAtMillis = nowMillis,
            ),
        )
        return sanitizedSnapshot
    }

    @Transaction
    suspend fun sealGeneration(generationId: String, expectedActiveManifestRevision: String, nowMillis: Long) {
        val generation = checkNotNull(generation(generationId))
        check(generation.state == VectorGenerationState.BUILDING)
        val active = checkNotNull(activeSnapshotId()?.let { snapshot(it) })
        val revision =
            if (generation.channel == IndexChannel.VISUAL) {
                active.visualManifestRevision
            } else {
                active.semanticManifestRevision
            }
        check(revision == expectedActiveManifestRevision)
        check(manifest(expectedActiveManifestRevision)?.generationId == generationId)
        check(sealGenerationRow(generationId, nowMillis) == 1)
    }

    @Transaction
    suspend fun registerActivationCandidate(
        candidate: ActivationCandidateEntity,
        channels: List<ActivationCandidateChannelEntity>,
    ) {
        require(identifier(candidate.candidateId) && identifier(candidate.snapshotId))
        require(candidate.parentSnapshotId == null || identifier(candidate.parentSnapshotId))
        require(identifier(candidate.packId) && contractValue(candidate.packVersion))
        require(sha256(candidate.packManifestSha256))
        require(candidate.capturedAccessRevision > 0 && candidate.capturedCatalogWatermark >= 0)
        require(candidate.state == ActivationCandidateState.BUILDING_SHADOW)
        require(candidate.createdAtMillis >= 0 && candidate.updatedAtMillis == candidate.createdAtMillis)
        require(candidate.failureCode == null)
        val pack = checkNotNull(modelPack(candidate.packId, candidate.packVersion))
        check(pack.manifestSha256 == candidate.packManifestSha256)
        check(activeSnapshotId() == candidate.parentSnapshotId)
        val access = checkNotNull(accessObservation())
        check(access.accessScope != "None" && access.processAccessRevision == candidate.capturedAccessRevision)
        check((catalogWatermark()?.catalogRevision ?: 0) == candidate.capturedCatalogWatermark)
        check(snapshot(candidate.snapshotId) == null)
        validateActivationCandidatePlan(candidate, channels)
        insertActivationCandidate(candidate)
        insertActivationCandidateChannels(channels)
    }

    @Transaction
    suspend fun markActivationCandidateReady(
        candidateId: String,
        snapshot: ActivationSnapshotEntity,
        channels: List<ActivationSnapshotChannelEntity>,
        nowMillis: Long,
    ): ActivationSnapshotEntity {
        val candidate = checkNotNull(activationCandidate(candidateId))
        check(candidate.state == ActivationCandidateState.BUILDING_SHADOW)
        check(activeSnapshotId() == candidate.parentSnapshotId)
        require(snapshot.createdAtMillis in candidate.createdAtMillis..nowMillis)
        validateActivationCandidateSnapshot(
            candidate,
            activationCandidateChannels(candidate.candidateId),
            snapshot,
            channels,
        )
        insertSnapshot(snapshot)
        insertSnapshotChannels(channels)
        check(
            transitionActivationCandidateRow(
                candidateId = candidateId,
                expectedState = ActivationCandidateState.BUILDING_SHADOW,
                newState = ActivationCandidateState.READY_TO_ACTIVATE,
                nowMillis = nowMillis,
                failureCode = null,
            ) == 1,
        )
        return snapshot
    }

    @Transaction
    suspend fun reconcileActivationCandidateWatermark(
        candidateId: String,
        expectedWatermark: Long,
        nextWatermark: Long,
        nowMillis: Long,
    ): ActivationCandidateEntity {
        require(expectedWatermark >= 0 && nextWatermark >= expectedWatermark)
        val candidate = checkNotNull(activationCandidate(candidateId))
        check(candidate.state == ActivationCandidateState.BUILDING_SHADOW)
        check(candidate.capturedCatalogWatermark == expectedWatermark)
        check(activeSnapshotId() == candidate.parentSnapshotId)
        val access = checkNotNull(accessObservation())
        check(access.accessScope != "None" && access.processAccessRevision == candidate.capturedAccessRevision)
        check((catalogWatermark()?.catalogRevision ?: 0) == nextWatermark)
        check(
            advanceActivationCandidateWatermarkRow(
                candidateId = candidateId,
                expectedWatermark = expectedWatermark,
                nextWatermark = nextWatermark,
                nowMillis = nowMillis,
            ) == 1,
        )
        return checkNotNull(activationCandidate(candidateId))
    }

    @Transaction
    suspend fun activateReadyCandidate(candidateId: String, nowMillis: Long): ActiveSnapshotPointerEntity {
        val candidate = checkNotNull(activationCandidate(candidateId))
        check(candidate.state == ActivationCandidateState.READY_TO_ACTIVATE)
        val pointer = activePointer()
        check(pointer?.snapshotId == candidate.parentSnapshotId)
        val access = checkNotNull(accessObservation())
        check(access.accessScope != "None" && access.processAccessRevision == candidate.capturedAccessRevision)
        check((catalogWatermark()?.catalogRevision ?: 0) == candidate.capturedCatalogWatermark)
        val snapshot = checkNotNull(snapshot(candidate.snapshotId))
        validateActivationCandidateSnapshot(
            candidate,
            activationCandidateChannels(candidate.candidateId),
            snapshot,
            snapshotChannels(snapshot.snapshotId),
        )
        check(deleteIntentCount(snapshot.snapshotId) == 0)
        val activated =
            ActiveSnapshotPointerEntity(
                snapshotId = snapshot.snapshotId,
                rollbackSnapshotId = candidate.parentSnapshotId,
                activationSequence = Math.addExact(pointer?.activationSequence ?: 0, 1),
                updatedAtMillis = nowMillis,
            )
        replaceActivePointer(activated)
        check(
            transitionActivationCandidateRow(
                candidateId = candidateId,
                expectedState = ActivationCandidateState.READY_TO_ACTIVATE,
                newState = ActivationCandidateState.ACTIVE,
                nowMillis = nowMillis,
                failureCode = null,
            ) == 1,
        )
        return activated
    }

    @Transaction
    suspend fun rollbackActiveCandidate(nowMillis: Long): ActiveSnapshotPointerEntity? {
        val pointer = activePointer() ?: return null
        val activeId = pointer.snapshotId ?: return null
        val rollbackId = pointer.rollbackSnapshotId ?: return null
        check(snapshot(rollbackId) != null && deleteIntentCount(rollbackId) == 0)
        val candidate = activationCandidateBySnapshot(activeId)
        if (candidate != null && candidate.state == ActivationCandidateState.ACTIVE) {
            check(
                transitionActivationCandidateRow(
                    candidateId = candidate.candidateId,
                    expectedState = ActivationCandidateState.ACTIVE,
                    newState = ActivationCandidateState.ROLLED_BACK,
                    nowMillis = nowMillis,
                    failureCode = "ACTIVE_ROLLBACK",
                ) == 1,
            )
        }
        val rolledBack = ActiveSnapshotPointerEntity(
            snapshotId = rollbackId,
            rollbackSnapshotId = snapshot(rollbackId)?.parentSnapshotId,
            activationSequence = Math.addExact(pointer.activationSequence, 1),
            updatedAtMillis = nowMillis,
        )
        replaceActivePointer(rolledBack)
        return rolledBack
    }

    @Transaction
    suspend fun rejectActivationCandidate(candidateId: String, failureCode: String, nowMillis: Long): Boolean {
        require(contractValue(failureCode))
        val candidate = activationCandidate(candidateId) ?: return false
        if (candidate.state !in setOf(
                ActivationCandidateState.BUILDING_SHADOW,
                ActivationCandidateState.READY_TO_ACTIVATE,
            )
        ) {
            return false
        }
        return transitionActivationCandidateRow(
            candidateId = candidateId,
            expectedState = candidate.state,
            newState = ActivationCandidateState.REJECTED,
            nowMillis = nowMillis,
            failureCode = failureCode,
        ) == 1
    }

    @Transaction
    suspend fun abandonStagedPublication(publicationToken: String, nowMillis: Long): Int {
        val publication = publication(publicationToken) ?: return 0
        if (publication.state != VectorPublicationState.STAGED) return 0
        resetStagedPublicationWork(publicationToken, nowMillis)
        check(abandonPublicationRow(publicationToken, nowMillis) == 1)
        return 1
    }

    @Transaction
    suspend fun acquireActiveSnapshotLease(lease: QuerySnapshotLeaseEntity, nowMillis: Long) {
        require(identifier(lease.leaseToken))
        require(lease.accessRevision > 0)
        require(lease.createdAtMillis == nowMillis)
        require(lease.expiresAtMillis in (nowMillis + 1)..Math.addExact(nowMillis, MaximumQueryLeaseMillis))
        check(activeSnapshotId() == lease.snapshotId)
        check(snapshot(lease.snapshotId) != null)
        val access = checkNotNull(accessObservation())
        check(access.accessScope != "None" && access.processAccessRevision == lease.accessRevision)
        replaceQueryLease(lease)
        check(queryLease(lease.leaseToken) == lease)
    }

    @Transaction
    suspend fun acquireCurrentSnapshotLease(
        leaseToken: String,
        nowMillis: Long,
        expiresAtMillis: Long,
    ): QuerySnapshotLeaseEntity? {
        require(identifier(leaseToken))
        require(expiresAtMillis in (nowMillis + 1)..Math.addExact(nowMillis, MaximumQueryLeaseMillis))
        val snapshotId = activeSnapshotId() ?: return null
        val access = accessObservation() ?: return null
        if (access.accessScope == "None" || access.processAccessRevision <= 0) return null
        return QuerySnapshotLeaseEntity(
            leaseToken = leaseToken,
            snapshotId = snapshotId,
            accessRevision = access.processAccessRevision,
            createdAtMillis = nowMillis,
            expiresAtMillis = expiresAtMillis,
        ).also { lease -> acquireActiveSnapshotLease(lease, nowMillis) }
    }

    @Transaction
    suspend fun renewCurrentSnapshotLease(
        leaseToken: String,
        nowMillis: Long,
        expiresAtMillis: Long,
    ): Boolean {
        require(identifier(leaseToken))
        require(expiresAtMillis in (nowMillis + 1)..Math.addExact(nowMillis, MaximumQueryLeaseMillis))
        val lease = queryLease(leaseToken) ?: return false
        if (lease.expiresAtMillis <= nowMillis) return false
        val access = accessObservation() ?: return false
        if (access.accessScope == "None" || access.processAccessRevision != lease.accessRevision) return false
        check(renewQueryLeaseRow(leaseToken, nowMillis, expiresAtMillis) == 1)
        return true
    }

    @Transaction
    suspend fun currentSemanticEvidence(
        manifestRevision: String,
        recordIds: List<Long>,
        semanticPipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        ocrPipelineVersion: String = "ocr-v1",
        ocrComponentHash: String = componentHash,
    ): List<SemanticVectorEvidence> {
        require(identifier(manifestRevision))
        require(recordIds.isNotEmpty() && recordIds.size <= MaximumSemanticCandidates)
        require(recordIds.all { it > 0 } && recordIds.distinct().size == recordIds.size)
        require(
            contractValue(semanticPipelineVersion) &&
                sha256(componentHash) &&
                contractValue(ocrPipelineVersion) &&
                sha256(ocrComponentHash),
        )
        require(maximumPublicationEpoch >= 0)
        val manifest = checkNotNull(manifest(manifestRevision))
        check(manifest.channel == IndexChannel.OCR_SEMANTIC)
        return semanticEvidenceRows(
            manifestRevision = manifestRevision,
            recordIds = recordIds,
            semanticPipelineVersion = semanticPipelineVersion,
            componentHash = componentHash,
            ocrPipelineVersion = ocrPipelineVersion,
            ocrComponentHash = ocrComponentHash,
            maximumPublicationEpoch = maximumPublicationEpoch,
        ).also { evidence ->
            check(evidence.all { row ->
                row.recordId in recordIds &&
                    row.segmentSha256.let(::sha256) &&
                    row.assetId > 0 &&
                    row.chunkOrdinal >= 0 &&
                    row.publicationEpoch in 0..maximumPublicationEpoch
            })
        }
    }

    @Transaction
    suspend fun currentEligibleSemanticRecordIds(
        manifestRevision: String,
        segmentSha256: String,
        semanticPipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        ocrPipelineVersion: String = "ocr-v1",
        ocrComponentHash: String = componentHash,
        takenFromMillis: Long? = null,
        takenBeforeMillis: Long? = null,
        bucketId: Long? = null,
        mimeType: String? = null,
    ): List<Long> {
        require(identifier(manifestRevision) && sha256(segmentSha256))
        require(
            contractValue(semanticPipelineVersion) &&
                sha256(componentHash) &&
                contractValue(ocrPipelineVersion) &&
                sha256(ocrComponentHash),
        )
        require(maximumPublicationEpoch >= 0)
        validateSearchFilters(takenFromMillis, takenBeforeMillis, bucketId, mimeType)
        val manifest = checkNotNull(manifest(manifestRevision))
        check(manifest.channel == IndexChannel.OCR_SEMANTIC)
        return semanticEligibleRecordIds(
            manifestRevision = manifestRevision,
            segmentSha256 = segmentSha256,
            semanticPipelineVersion = semanticPipelineVersion,
            componentHash = componentHash,
            ocrPipelineVersion = ocrPipelineVersion,
            ocrComponentHash = ocrComponentHash,
            maximumPublicationEpoch = maximumPublicationEpoch,
            takenFromMillis = takenFromMillis,
            takenBeforeMillis = takenBeforeMillis,
            bucketId = bucketId,
            mimeType = mimeType,
        ).also { recordIds ->
            check(recordIds.size <= MaximumSegmentRecords)
            check(recordIds.all { it > 0 } && recordIds.zipWithNext().all { (left, right) -> left < right })
        }
    }

    @Transaction
    suspend fun currentVisualEvidence(
        manifestRevision: String,
        recordIds: List<Long>,
        visualPipelineVersion: String,
        componentHash: String,
    ): List<VisualVectorEvidence> {
        require(identifier(manifestRevision))
        require(recordIds.isNotEmpty() && recordIds.size <= MaximumVisualCandidates)
        require(recordIds.all { it > 0 } && recordIds.distinct().size == recordIds.size)
        require(contractValue(visualPipelineVersion) && sha256(componentHash))
        val manifest = checkNotNull(manifest(manifestRevision))
        check(manifest.channel == IndexChannel.VISUAL)
        return visualEvidenceRows(
            manifestRevision = manifestRevision,
            recordIds = recordIds,
            visualPipelineVersion = visualPipelineVersion,
            componentHash = componentHash,
        ).also { evidence ->
            check(evidence.all { row ->
                row.recordId in recordIds &&
                    row.recordId == row.assetId &&
                    row.segmentSha256.let(::sha256) &&
                    row.sourceFingerprint.isNotBlank()
            })
        }
    }

    @Transaction
    suspend fun currentEligibleVisualRecordIds(
        manifestRevision: String,
        segmentSha256: String,
        visualPipelineVersion: String,
        componentHash: String,
        takenFromMillis: Long? = null,
        takenBeforeMillis: Long? = null,
        bucketId: Long? = null,
        mimeType: String? = null,
    ): List<Long> {
        require(identifier(manifestRevision) && sha256(segmentSha256))
        require(contractValue(visualPipelineVersion) && sha256(componentHash))
        validateSearchFilters(takenFromMillis, takenBeforeMillis, bucketId, mimeType)
        val manifest = checkNotNull(manifest(manifestRevision))
        check(manifest.channel == IndexChannel.VISUAL)
        return visualEligibleRecordIds(
            manifestRevision = manifestRevision,
            segmentSha256 = segmentSha256,
            visualPipelineVersion = visualPipelineVersion,
            componentHash = componentHash,
            takenFromMillis = takenFromMillis,
            takenBeforeMillis = takenBeforeMillis,
            bucketId = bucketId,
            mimeType = mimeType,
        ).also { recordIds ->
            check(recordIds.size <= MaximumSegmentRecords)
            check(recordIds.all { it > 0 } && recordIds.zipWithNext().all { (left, right) -> left < right })
        }
    }

    private fun validateSearchFilters(
        takenFromMillis: Long?,
        takenBeforeMillis: Long?,
        bucketId: Long?,
        mimeType: String?,
    ) {
        require(takenFromMillis == null || takenFromMillis >= 0)
        require(takenBeforeMillis == null || takenBeforeMillis >= 0)
        require(takenFromMillis == null || takenBeforeMillis == null || takenFromMillis < takenBeforeMillis)
        require(bucketId == null || bucketId >= 0)
        require(mimeType == null || MimeType.matches(mimeType))
    }

    @Transaction
    suspend fun replaceActiveAfterRecovery(
        expectedActiveSnapshotId: String?,
        recoveredSnapshotId: String?,
        nowMillis: Long,
    ): Boolean {
        if (activeSnapshotId() != expectedActiveSnapshotId) return false
        if (recoveredSnapshotId != null) {
            check(snapshot(recoveredSnapshotId) != null)
            if (deleteIntentCount(recoveredSnapshotId) != 0) return false
        }
        val current = activePointer()
        val activeCandidate = expectedActiveSnapshotId?.let { activationCandidateBySnapshot(it) }
        if (
            expectedActiveSnapshotId != recoveredSnapshotId &&
            activeCandidate?.state == ActivationCandidateState.ACTIVE
        ) {
            check(
                transitionActivationCandidateRow(
                    candidateId = activeCandidate.candidateId,
                    expectedState = ActivationCandidateState.ACTIVE,
                    newState = ActivationCandidateState.ROLLED_BACK,
                    nowMillis = nowMillis,
                    failureCode = "STARTUP_AUDIT_FAILED",
                ) == 1,
            )
        }
        replaceActivePointer(
            ActiveSnapshotPointerEntity(
                snapshotId = recoveredSnapshotId,
                rollbackSnapshotId = recoveredSnapshotId?.let { snapshot(it)?.parentSnapshotId },
                activationSequence = Math.addExact(current?.activationSequence ?: 0, 1),
                updatedAtMillis = nowMillis,
            ),
        )
        return true
    }

    @Transaction
    suspend fun prepareSnapshotCollection(snapshotId: String, nowMillis: Long): List<ArtifactDeleteIntentEntity> {
        require(identifier(snapshotId))
        val candidate = snapshot(snapshotId) ?: return emptyList()
        val activeId = activeSnapshotId()
        check(snapshotId != activeId)
        val rollbackId = activePointer()?.rollbackSnapshotId ?: activeId?.let { snapshot(it)?.parentSnapshotId }
        check(snapshotId != rollbackId)
        check(liveQueryLeaseCount(snapshotId, nowMillis) == 0)
        check(activationRootCount(snapshotId) == 0)
        val revisions = listOfNotNull(candidate.semanticManifestRevision, candidate.visualManifestRevision).distinct()
        val intents = mutableListOf<ArtifactDeleteIntentEntity>()
        revisions.forEach { revision ->
            if (otherSnapshotManifestReferenceCount(revision, snapshotId) == 0) {
                val manifest = checkNotNull(manifest(revision))
                manifestSegments(revision).forEach { entry ->
                    if (otherManifestSegmentReferenceCount(entry.segmentSha256, revision) == 0) {
                        val artifact = checkNotNull(segment(entry.segmentSha256))
                        intents += ArtifactDeleteIntentEntity(
                            relativePath = artifact.relativePath,
                            ownerSnapshotId = snapshotId,
                            expectedSha256 = artifact.sha256,
                            state = ArtifactDeleteState.PENDING,
                            createdAtMillis = nowMillis,
                        )
                    }
                }
                intents += ArtifactDeleteIntentEntity(
                    relativePath = manifest.relativePath,
                    ownerSnapshotId = snapshotId,
                    expectedSha256 = manifest.sha256,
                    state = ArtifactDeleteState.PENDING,
                    createdAtMillis = nowMillis,
                )
            }
        }
        if (intents.isNotEmpty()) insertDeleteIntents(intents.distinctBy(ArtifactDeleteIntentEntity::relativePath))
        return deleteIntents(snapshotId)
    }

    @Transaction
    suspend fun finalizeSnapshotCollection(snapshotId: String): Boolean {
        val candidate = snapshot(snapshotId) ?: run {
            deleteDeleteIntents(snapshotId)
            return true
        }
        val intents = deleteIntents(snapshotId)
        check(intents.all { it.state == ArtifactDeleteState.CONFIRMED })
        val activeId = activeSnapshotId()
        check(snapshotId != activeId)
        val rollbackId = activePointer()?.rollbackSnapshotId ?: activeId?.let { snapshot(it)?.parentSnapshotId }
        check(snapshotId != rollbackId)
        check(activationRootCount(snapshotId) == 0)
        val revisions = listOfNotNull(candidate.semanticManifestRevision, candidate.visualManifestRevision).distinct()
        deleteSnapshotChannels(snapshotId)
        check(deleteSnapshot(snapshotId) == 1)
        revisions.forEach { revision ->
            if (otherSnapshotManifestReferenceCount(revision, snapshotId) == 0) {
                val entries = manifestSegments(revision)
                deleteManifestSegments(revision)
                check(deleteManifest(revision) == 1)
                entries.forEach { entry ->
                    if (segmentReferenceCount(entry.segmentSha256) == 0) {
                        deleteSegmentRecords(entry.segmentSha256)
                        check(deleteSegment(entry.segmentSha256) == 1)
                    }
                }
            }
        }
        deleteDeleteIntents(snapshotId)
        return true
    }

    private suspend fun validateManifest(
        generation: VectorGenerationEntity,
        manifest: VectorManifestEntity,
        entries: List<VectorManifestSegmentEntity>,
    ) {
        require(identifier(manifest.revision))
        require(manifest.generationId == generation.generationId)
        require(manifest.channel == generation.channel)
        require(manifest.parentRevision == null || identifier(manifest.parentRevision))
        require(relativeArtifactPath(manifest.relativePath))
        require(manifest.byteLength > 0 && sha256(manifest.sha256))
        require(entries.isNotEmpty() && entries.size <= MaximumManifestSegments)
        require(manifest.segmentCount == entries.size)
        require(manifest.recordCount > 0)
        require(entries.all { it.manifestRevision == manifest.revision && it.ordinal >= 0 })
        require(entries.map(VectorManifestSegmentEntity::ordinal) == entries.indices.toList())
        require(entries.map(VectorManifestSegmentEntity::segmentSha256).distinct().size == entries.size)
        require(entries.all { sha256(it.segmentSha256) })
        val actualRecordCount = entries.sumOf { checkNotNull(segment(it.segmentSha256)).recordCount.toLong() }
        check(actualRecordCount == manifest.recordCount)
        if (manifest.parentRevision != null) {
            val parent = checkNotNull(manifest(manifest.parentRevision))
            check(parent.generationId == generation.generationId && parent.channel == generation.channel)
        }
    }

    private suspend fun validateSnapshot(
        candidate: ActivationSnapshotEntity,
        generation: VectorGenerationEntity,
        manifest: VectorManifestEntity,
    ) {
        require(identifier(candidate.snapshotId))
        require(candidate.parentSnapshotId == null || identifier(candidate.parentSnapshotId))
        require(candidate.packId == generation.packId && candidate.packVersion == generation.packVersion)
        require(sha256(candidate.packManifestSha256))
        require(candidate.engineContractVersion > 0)
        require(contractValue(candidate.rankingConfigVersion))
        require(candidate.lexicalPublicationEpoch >= 0 && candidate.pHashPublicationEpoch >= 0)
        require(candidate.catalogWatermark >= 0 && candidate.createdAtMillis >= 0)
        require(candidate.formatVersion == ActivationSnapshotFormat.Current)
        val pack = checkNotNull(modelPack(candidate.packId, candidate.packVersion))
        check(pack.manifestSha256 == candidate.packManifestSha256)
        val activeId = activeSnapshotId()
        check(activeId == candidate.parentSnapshotId)
        val parent = activeId?.let { checkNotNull(snapshot(it)) }
        check(candidate.semanticManifestRevision == if (generation.channel == IndexChannel.OCR_SEMANTIC) manifest.revision else parent?.semanticManifestRevision)
        check(candidate.visualManifestRevision == if (generation.channel == IndexChannel.VISUAL) manifest.revision else parent?.visualManifestRevision)
        if (parent != null) {
            check(parent.packId == candidate.packId && parent.packVersion == candidate.packVersion)
            check(parent.packManifestSha256 == candidate.packManifestSha256)
            check(parent.engineContractVersion == candidate.engineContractVersion)
            check(parent.rankingConfigVersion == candidate.rankingConfigVersion)
            check(candidate.lexicalPublicationEpoch >= parent.lexicalPublicationEpoch)
            check(candidate.pHashPublicationEpoch >= parent.pHashPublicationEpoch)
            check(candidate.catalogWatermark >= parent.catalogWatermark)
        }
    }

    private suspend fun validateActivationCandidatePlan(
        candidate: ActivationCandidateEntity,
        channels: List<ActivationCandidateChannelEntity>,
    ) {
        require(channels.isNotEmpty() && channels.map { it.channel }.distinct().size == channels.size)
        require(channels.all { component ->
            component.candidateId == candidate.candidateId &&
                component.channel in IndexChannel.all &&
                contractValue(component.pipelineVersion) &&
                sha256(component.componentHash) &&
                (component.embeddingSpaceHash == null || sha256(component.embeddingSpaceHash)) &&
                component.action in ActivationCandidateChannelAction.all &&
                contractValue(component.reason)
        })
        val parentChannels = candidate.parentSnapshotId?.let { snapshotChannels(it) }.orEmpty()
        val parentByChannel = parentChannels.associateBy(ActivationSnapshotChannelEntity::channel)
        channels.forEach { planned ->
            val parent = parentByChannel[planned.channel]
            if (planned.action == ActivationCandidateChannelAction.INHERIT) {
                check(parent != null)
                check(
                    parent.pipelineVersion == planned.pipelineVersion &&
                        parent.componentHash == planned.componentHash &&
                        parent.embeddingSpaceHash == planned.embeddingSpaceHash,
                )
            }
        }
    }

    private suspend fun validateActivationCandidateSnapshot(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
        snapshot: ActivationSnapshotEntity,
        channels: List<ActivationSnapshotChannelEntity>,
    ) {
        require(snapshot.formatVersion == ActivationSnapshotFormat.Current)
        require(identifier(snapshot.snapshotId) && snapshot.snapshotId == candidate.snapshotId)
        require(snapshot.parentSnapshotId == candidate.parentSnapshotId)
        require(snapshot.packId == candidate.packId && snapshot.packVersion == candidate.packVersion)
        require(snapshot.packManifestSha256 == candidate.packManifestSha256)
        require(snapshot.engineContractVersion > 0 && contractValue(snapshot.rankingConfigVersion))
        require(snapshot.lexicalPublicationEpoch >= 0 && snapshot.pHashPublicationEpoch >= 0)
        require(snapshot.catalogWatermark == candidate.capturedCatalogWatermark)
        require(snapshot.capturedAccessRevision == candidate.capturedAccessRevision)
        require(snapshot.createdAtMillis >= candidate.createdAtMillis)
        check(modelPack(snapshot.packId, snapshot.packVersion)?.manifestSha256 == snapshot.packManifestSha256)
        validateActivationCandidatePlan(candidate, plan)
        require(channels.isNotEmpty() && channels.map { it.channel }.distinct().size == channels.size)
        val expectedChannels =
            buildSet {
                add(IndexChannel.OCR)
                add(IndexChannel.PHASH)
                if (snapshot.semanticManifestRevision != null) add(IndexChannel.OCR_SEMANTIC)
                if (snapshot.visualManifestRevision != null) add(IndexChannel.VISUAL)
            }
        require(channels.map(ActivationSnapshotChannelEntity::channel).toSet() == expectedChannels)
        require(plan.map(ActivationCandidateChannelEntity::channel).toSet() == expectedChannels)
        require(channels.all { component ->
            component.snapshotId == snapshot.snapshotId &&
                component.channel in IndexChannel.all &&
                contractValue(component.pipelineVersion) &&
                sha256(component.componentHash) &&
                (component.embeddingSpaceHash == null || sha256(component.embeddingSpaceHash)) &&
                (component.generationId == null || identifier(component.generationId)) &&
                (component.manifestRevision == null || identifier(component.manifestRevision)) &&
                (component.inheritedFromSnapshotId == null || identifier(component.inheritedFromSnapshotId))
        })
        val plannedByChannel = plan.associateBy(ActivationCandidateChannelEntity::channel)
        channels.forEach { component ->
            val planned = checkNotNull(plannedByChannel[component.channel])
            check(
                component.pipelineVersion == planned.pipelineVersion &&
                    component.componentHash == planned.componentHash &&
                    component.embeddingSpaceHash == planned.embeddingSpaceHash,
            )
            if (planned.action == ActivationCandidateChannelAction.INHERIT) {
                check(component.inheritedFromSnapshotId == candidate.parentSnapshotId)
            } else {
                check(component.inheritedFromSnapshotId == null)
            }
            validateActivationComponent(snapshot, component)
        }
        val parentSnapshot = candidate.parentSnapshotId?.let { checkNotNull(snapshot(it)) }
        if (parentSnapshot != null) {
            check(snapshot.lexicalPublicationEpoch >= parentSnapshot.lexicalPublicationEpoch)
            check(snapshot.pHashPublicationEpoch >= parentSnapshot.pHashPublicationEpoch)
        }
        val ocrPlan = checkNotNull(plannedByChannel[IndexChannel.OCR])
        if (ocrPlan.action == ActivationCandidateChannelAction.REBUILD_SHADOW) {
            check(snapshot.lexicalPublicationEpoch <= (publicationClock()?.lastEpoch ?: 0))
            check(
                coveredOcrAssetCount(
                    pipelineVersion = ocrPlan.pipelineVersion,
                    componentHash = ocrPlan.componentHash,
                    maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
                ) == availableAssetCount(),
            ) { "Changed OCR candidate does not cover the accessible cutover set" }
        }
        check(snapshot.semanticManifestRevision != null || snapshot.visualManifestRevision != null)
    }

    private suspend fun validateActivationComponent(
        snapshot: ActivationSnapshotEntity,
        component: ActivationSnapshotChannelEntity,
    ) {
        if (component.inheritedFromSnapshotId != null) {
            check(component.inheritedFromSnapshotId == snapshot.parentSnapshotId)
            val parent = checkNotNull(snapshotChannel(component.inheritedFromSnapshotId, component.channel))
            check(
                parent.pipelineVersion == component.pipelineVersion &&
                    parent.componentHash == component.componentHash &&
                    parent.embeddingSpaceHash == component.embeddingSpaceHash &&
                    parent.generationId == component.generationId &&
                    parent.manifestRevision == component.manifestRevision,
            )
        }
        if (component.channel !in setOf(IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL)) {
            check(
                component.embeddingSpaceHash == null &&
                    component.generationId == null &&
                    component.manifestRevision == null,
            )
            return
        }
        val expectedRevision =
            if (component.channel == IndexChannel.VISUAL) snapshot.visualManifestRevision else snapshot.semanticManifestRevision
        check(component.manifestRevision == expectedRevision)
        val manifest = checkNotNull(component.manifestRevision?.let { manifest(it) })
        val generation = checkNotNull(generation(manifest.generationId))
        check(component.generationId == generation.generationId)
        check(manifest.channel == component.channel && generation.channel == component.channel)
        check(generation.pipelineVersion == component.pipelineVersion)
        check(generation.componentHash == component.componentHash)
        check(generation.embeddingSpaceHash == component.embeddingSpaceHash)
        check(generation.state in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED))
        check(manifestSegments(manifest.revision).size == manifest.segmentCount)
    }

    private suspend fun buildPublishedSnapshotChannels(
        snapshot: ActivationSnapshotEntity,
        generation: VectorGenerationEntity,
        manifest: VectorManifestEntity,
    ): List<ActivationSnapshotChannelEntity> {
        val parentChannels = snapshot.parentSnapshotId?.let { snapshotChannels(it) }.orEmpty()
        val channels = parentChannels.associateBy(ActivationSnapshotChannelEntity::channel).toMutableMap()
        if (IndexChannel.OCR !in channels) {
            channels[IndexChannel.OCR] =
                ActivationSnapshotChannelEntity(
                    snapshotId = snapshot.snapshotId,
                    channel = IndexChannel.OCR,
                    pipelineVersion = "ocr-v1",
                    componentHash = snapshot.packManifestSha256,
                    embeddingSpaceHash = null,
                    generationId = null,
                    manifestRevision = null,
                    inheritedFromSnapshotId = null,
                )
        }
        if (IndexChannel.PHASH !in channels) {
            channels[IndexChannel.PHASH] =
                ActivationSnapshotChannelEntity(
                    snapshotId = snapshot.snapshotId,
                    channel = IndexChannel.PHASH,
                    pipelineVersion = "phash-v1",
                    componentHash = PerceptualHashComponentHash,
                    embeddingSpaceHash = null,
                    generationId = null,
                    manifestRevision = null,
                    inheritedFromSnapshotId = null,
                )
        }
        parentChannels.forEach { parent ->
            channels[parent.channel] =
                parent.copy(
                    snapshotId = snapshot.snapshotId,
                    inheritedFromSnapshotId = snapshot.parentSnapshotId,
                )
        }
        channels[generation.channel] =
            ActivationSnapshotChannelEntity(
                snapshotId = snapshot.snapshotId,
                channel = generation.channel,
                pipelineVersion = generation.pipelineVersion,
                componentHash = generation.componentHash,
                embeddingSpaceHash = generation.embeddingSpaceHash,
                generationId = generation.generationId,
                manifestRevision = manifest.revision,
                inheritedFromSnapshotId = null,
            )
        return channels.values.sortedBy(ActivationSnapshotChannelEntity::channel)
    }

    private suspend fun advanceActivePointer(snapshotId: String, nowMillis: Long) {
        val current = activePointer()
        replaceActivePointer(
            ActiveSnapshotPointerEntity(
                snapshotId = snapshotId,
                rollbackSnapshotId = current?.snapshotId,
                activationSequence = Math.addExact(current?.activationSequence ?: 0, 1),
                updatedAtMillis = nowMillis,
            ),
        )
    }

    private fun validateSegment(
        generation: VectorGenerationEntity,
        segment: VectorSegmentArtifactEntity,
        records: List<VectorSegmentRecordEntity>,
    ) {
        require(sha256(segment.sha256))
        require(identifier(segment.segmentId))
        require(relativeArtifactPath(segment.relativePath))
        require(segment.byteLength > 0)
        require(segment.formatVersion == 1)
        require(segment.channel == generation.channel)
        require(segment.embeddingSpaceHash == generation.embeddingSpaceHash)
        require(segment.dimension == generation.dimension)
        require(segment.recordCount in 1..MaximumSegmentRecords)
        require(segment.compactionLevel >= 0)
        require(records.size == segment.recordCount)
        require(records.map(VectorSegmentRecordEntity::ordinal) == records.indices.toList())
        require(records.all { record ->
            record.segmentSha256 == segment.sha256 &&
                record.recordId > 0 && record.assetId > 0 && record.accessRevision > 0 && record.chunkOrdinal >= 0 &&
                record.sourceFingerprint.isNotBlank() && record.sourceFingerprint.length <= 128
        })
        require(records.map(VectorSegmentRecordEntity::recordId).distinct().size == records.size)
        require(
            generation.channel != IndexChannel.VISUAL ||
                records.all {
                    it.recordId == it.assetId && it.chunkOrdinal == 0 && it.semanticChunkId == null
                },
        )
        require(
            generation.channel != IndexChannel.OCR_SEMANTIC ||
                records.all { it.semanticChunkId != null && sha256(it.semanticChunkId) },
        )
    }

    private suspend fun validateSemanticRecordReferences(
        generation: VectorGenerationEntity,
        records: List<VectorSegmentRecordEntity>,
    ) {
        if (generation.channel != IndexChannel.OCR_SEMANTIC) return
        records.forEach { record ->
            val chunk = checkNotNull(semanticChunk(checkNotNull(record.semanticChunkId)))
            check(chunk.assetId == record.assetId)
            check(chunk.sourceFingerprint == record.sourceFingerprint)
            check(chunk.ordinal == record.chunkOrdinal)
        }
    }

    private fun identifier(value: String): Boolean = value.length in 1..96 && Identifier.matches(value)

    private fun contractValue(value: String): Boolean = value.length in 1..128 && ContractValue.matches(value)

    private fun sha256(value: String): Boolean = Sha256.matches(value)

    private fun relativeArtifactPath(value: String): Boolean =
        value.length in 1..240 && !value.startsWith('/') && !value.contains("..") && ArtifactPath.matches(value)

    companion object {
        private val VectorChannels = setOf(IndexChannel.VISUAL, IndexChannel.OCR_SEMANTIC)
        private val Identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]*")
        private val Sha256 = Regex("[0-9a-f]{64}")
        private val ArtifactPath = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")
        private val MimeType = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
        private const val PerceptualHashComponentHash =
            "b88379e5ff4d030a0193e528514079b18d5c0619d4500357381d0b4ec82b656a"
        const val MaximumVectorDimension = 4096
        const val MaximumSegmentRecords = 256
        const val MaximumManifestSegments = 65_536
        const val MaximumSemanticCandidates = 512
        const val MaximumVisualCandidates = 512
        const val MaximumQueryLeaseMillis = 5 * 60 * 1_000L
    }
}
