package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface PerceptualHashDao {
    @Query("SELECT * FROM index_channel_work WHERE leaseToken = :leaseToken")
    suspend fun workByLease(leaseToken: String): IndexChannelWorkEntity?

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun catalogAsset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun accessObservation(): CatalogAccessObservationEntity?

    @Query("SELECT * FROM index_channel_publication WHERE publicationToken = :publicationToken")
    suspend fun publicationByToken(publicationToken: String): IndexChannelPublicationEntity?

    @Query("SELECT * FROM index_publication_clock WHERE singletonId = 1")
    suspend fun publicationClock(): IndexPublicationClockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePublication(publication: IndexChannelPublicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePublicationClock(clock: IndexPublicationClockEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResult(result: PerceptualHashEntity)

    @Query(
        "UPDATE index_channel_work SET state = 'DONE', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, publicationToken = :publicationToken, stagedArtifactPath = NULL, " +
            "stagedArtifactLength = NULL, stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, " +
            "errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE assetId = :assetId AND channel = 'PHASH' AND state = 'RUNNING' " +
            "AND leaseToken = :leaseToken AND leaseExpiresAtMillis > :nowMillis " +
            "AND sourceFingerprint = :sourceFingerprint AND accessRevision = :accessRevision " +
            "AND pipelineVersion = :pipelineVersion AND componentHash = :componentHash",
    )
    suspend fun completeWork(
        assetId: Long,
        leaseToken: String,
        publicationToken: String,
        sourceFingerprint: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
        nowMillis: Long,
    ): Int

    @Query(
        "SELECT result.assetId AS assetId, result.hashBits AS hashBits, " +
            "result.publicationEpoch AS publicationEpoch " +
            "FROM perceptual_hash_result AS result " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = result.assetId " +
            "INNER JOIN index_channel_work AS work " +
            "ON work.assetId = result.assetId AND work.channel = 'PHASH' " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE result.pipelineVersion = :pipelineVersion " +
            "AND result.componentHash = :componentHash " +
            "AND result.publicationEpoch <= :maximumPublicationEpoch " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND asset.sourceFingerprint = result.sourceFingerprint " +
            "AND work.state = 'DONE' " +
            "AND work.sourceFingerprint = result.sourceFingerprint " +
            "AND work.accessRevision = result.accessRevision " +
            "AND work.pipelineVersion = result.pipelineVersion " +
            "AND work.componentHash = result.componentHash " +
            "AND result.accessRevision = access.processAccessRevision " +
            "AND access.accessScope != 'None' " +
            "ORDER BY result.assetId, result.publicationEpoch DESC",
    )
    suspend fun currentRows(
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
    ): List<CurrentPerceptualHash>

    @Query(
        "SELECT COALESCE(MAX(publicationEpoch), 0) FROM index_channel_publication " +
            "WHERE channel = 'PHASH' AND pipelineVersion = :pipelineVersion AND componentHash = :componentHash",
    )
    suspend fun maximumPublicationEpoch(pipelineVersion: String, componentHash: String): Long

    @Transaction
    suspend fun commit(
        leaseToken: String,
        publicationToken: String,
        draft: PerceptualHashDraft,
        expectedIdentity: PerceptualHashIdentity,
        nowMillis: Long,
    ): IndexChannelPublicationEntity? {
        require(PublicationToken.matches(publicationToken))
        require(Sha256.matches(expectedIdentity.sha256) && expectedIdentity.byteLength > 0)
        val actualIdentity = PerceptualHashCodec.identity(draft)
        require(actualIdentity == expectedIdentity)
        val work = workByLease(leaseToken) ?: return null
        if (
            work.channel != IndexChannel.PHASH ||
            work.state != IndexWorkState.RUNNING ||
            (work.leaseExpiresAtMillis ?: 0) <= nowMillis
        ) {
            return null
        }
        val asset = catalogAsset(work.assetId) ?: return null
        val access = accessObservation() ?: return null
        if (
            draft.assetId != work.assetId ||
            draft.sourceFingerprint != work.sourceFingerprint ||
            draft.accessRevision != work.accessRevision ||
            draft.pipelineVersion != work.pipelineVersion ||
            draft.componentHash != work.componentHash ||
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != work.sourceFingerprint ||
            access.processAccessRevision != work.accessRevision ||
            access.accessScope == "None" ||
            publicationByToken(publicationToken) != null
        ) {
            return null
        }
        val epoch = Math.addExact(publicationClock()?.lastEpoch ?: 0, 1)
        val publication =
            IndexChannelPublicationEntity(
                assetId = work.assetId,
                channel = IndexChannel.PHASH,
                publicationToken = publicationToken,
                sourceFingerprint = work.sourceFingerprint,
                accessRevision = work.accessRevision,
                pipelineVersion = work.pipelineVersion,
                componentHash = work.componentHash,
                resultSha256 = actualIdentity.sha256,
                resultLength = actualIdentity.byteLength,
                publicationEpoch = epoch,
                publishedAtMillis = nowMillis,
            )
        insertResult(
            PerceptualHashEntity(
                assetId = draft.assetId,
                sourceFingerprint = draft.sourceFingerprint,
                accessRevision = draft.accessRevision,
                pipelineVersion = draft.pipelineVersion,
                componentHash = draft.componentHash,
                hashBits = draft.hashBits,
                publicationEpoch = epoch,
                createdAtMillis = nowMillis,
            ),
        )
        replacePublication(publication)
        check(
            completeWork(
                assetId = work.assetId,
                leaseToken = leaseToken,
                publicationToken = publicationToken,
                sourceFingerprint = work.sourceFingerprint,
                accessRevision = work.accessRevision,
                pipelineVersion = work.pipelineVersion,
                componentHash = work.componentHash,
                nowMillis = nowMillis,
            ) == 1,
        )
        replacePublicationClock(IndexPublicationClockEntity(lastEpoch = epoch))
        return publication
    }

    @Transaction
    suspend fun current(
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
    ): List<CurrentPerceptualHash> {
        require(pipelineVersion.isNotBlank() && pipelineVersion.length <= 128)
        require(Sha256.matches(componentHash) && maximumPublicationEpoch >= 0)
        require(maximumPublicationEpoch <= (publicationClock()?.lastEpoch ?: 0))
        return currentRows(pipelineVersion, componentHash, maximumPublicationEpoch)
            .distinctBy(CurrentPerceptualHash::assetId)
    }

    private companion object {
        val PublicationToken = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
        val Sha256 = Regex("[0-9a-f]{64}")
    }
}
