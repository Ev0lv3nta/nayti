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

    @Query("SELECT * FROM activation_snapshot WHERE snapshotId = :snapshotId")
    suspend fun snapshot(snapshotId: String): ActivationSnapshotEntity?

    @Query("SELECT * FROM activation_snapshot ORDER BY createdAtMillis, snapshotId")
    suspend fun snapshots(): List<ActivationSnapshotEntity>

    @Query("SELECT * FROM vector_segment_artifact ORDER BY createdAtMillis, sha256")
    suspend fun segments(): List<VectorSegmentArtifactEntity>

    @Query("SELECT snapshotId FROM active_snapshot_pointer WHERE singletonId = 1")
    suspend fun activeSnapshotId(): String?

    @Upsert
    suspend fun replaceActivePointer(pointer: ActiveSnapshotPointerEntity)

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

    @Upsert
    suspend fun replaceQueryLease(lease: QuerySnapshotLeaseEntity)

    @Query("SELECT * FROM query_snapshot_lease WHERE leaseToken = :leaseToken")
    suspend fun queryLease(leaseToken: String): QuerySnapshotLeaseEntity?

    @Query("DELETE FROM query_snapshot_lease WHERE leaseToken = :leaseToken")
    suspend fun releaseQueryLease(leaseToken: String): Int

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
            check(records.filter { it.assetId == item.assetId }.all { it.sourceFingerprint == item.sourceFingerprint })
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
        replaceActivePointer(ActiveSnapshotPointerEntity(snapshotId = snapshot.snapshotId))
        check(completePublicationWork(publicationToken, nowMillis) == work.size)
        check(completePublicationRow(publicationToken, nowMillis) == 1)
        return snapshot
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
        check(manifest.recordCount == parentManifest.recordCount)

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
        replaceActivePointer(ActiveSnapshotPointerEntity(snapshotId = candidateSnapshot.snapshotId))
        return candidateSnapshot
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
    suspend fun replaceActiveAfterRecovery(expectedActiveSnapshotId: String?, recoveredSnapshotId: String?): Boolean {
        if (activeSnapshotId() != expectedActiveSnapshotId) return false
        if (recoveredSnapshotId != null) {
            check(snapshot(recoveredSnapshotId) != null)
            if (deleteIntentCount(recoveredSnapshotId) != 0) return false
        }
        replaceActivePointer(ActiveSnapshotPointerEntity(snapshotId = recoveredSnapshotId))
        return true
    }

    @Transaction
    suspend fun prepareSnapshotCollection(snapshotId: String, nowMillis: Long): List<ArtifactDeleteIntentEntity> {
        require(identifier(snapshotId))
        val candidate = snapshot(snapshotId) ?: return emptyList()
        val activeId = activeSnapshotId()
        check(snapshotId != activeId)
        val activeParentId = activeId?.let { snapshot(it)?.parentSnapshotId }
        check(snapshotId != activeParentId)
        check(liveQueryLeaseCount(snapshotId, nowMillis) == 0)
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
        check(snapshotId != activeId?.let { snapshot(it)?.parentSnapshotId })
        val revisions = listOfNotNull(candidate.semanticManifestRevision, candidate.visualManifestRevision).distinct()
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
        require(records.size == segment.recordCount)
        require(records.map(VectorSegmentRecordEntity::ordinal) == records.indices.toList())
        require(records.all { record ->
            record.segmentSha256 == segment.sha256 &&
                record.recordId > 0 && record.assetId > 0 && record.chunkOrdinal >= 0 &&
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
        const val MaximumVectorDimension = 4096
        const val MaximumSegmentRecords = 256
        const val MaximumManifestSegments = 65_536
        const val MaximumQueryLeaseMillis = 5 * 60 * 1_000L
    }
}
