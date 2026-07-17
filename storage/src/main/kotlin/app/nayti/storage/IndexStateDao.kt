package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
interface IndexStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: IndexOperationEntity)

    @Query("SELECT * FROM index_operation WHERE operationId = :operationId")
    suspend fun operation(operationId: String): IndexOperationEntity?

    @Query("UPDATE index_operation SET state = :state, updatedAtMillis = :nowMillis WHERE operationId = :operationId")
    suspend fun updateOperationState(operationId: String, state: String, nowMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecutionWindow(window: IndexExecutionWindowEntity)

    @Query("SELECT * FROM index_execution_window WHERE windowId = :windowId")
    suspend fun executionWindow(windowId: String): IndexExecutionWindowEntity?

    @Query(
        "SELECT COUNT(*) FROM index_execution_window " +
            "WHERE operationId = :operationId AND state = 'RUNNING' AND expiresAtMillis > :nowMillis",
    )
    suspend fun liveExecutionWindowCount(operationId: String, nowMillis: Long): Int

    @Query(
        "UPDATE index_execution_window SET state = 'EXPIRED', finishedAtMillis = :nowMillis " +
            "WHERE state = 'RUNNING' AND expiresAtMillis <= :nowMillis",
    )
    suspend fun expireExecutionWindows(nowMillis: Long): Int

    @Query(
        "UPDATE index_execution_window SET state = :state, finishedAtMillis = :nowMillis " +
            "WHERE windowId = :windowId AND state = 'RUNNING'",
    )
    suspend fun stopExecutionWindowRow(windowId: String, state: String, nowMillis: Long): Int

    @Upsert
    suspend fun replaceWork(work: IndexChannelWorkEntity)

    @Query("SELECT * FROM index_channel_work WHERE assetId = :assetId AND channel = :channel")
    suspend fun work(assetId: Long, channel: String): IndexChannelWorkEntity?

    @Query("SELECT * FROM index_channel_work WHERE leaseToken = :leaseToken")
    suspend fun workByLease(leaseToken: String): IndexChannelWorkEntity?

    @Query(
        "SELECT work.* FROM index_channel_work AS work " +
            "INNER JOIN catalog_asset AS asset ON asset.assetId = work.assetId " +
            "INNER JOIN catalog_access_observation AS access ON access.singletonId = 1 " +
            "WHERE work.channel = :channel " +
            "AND work.state IN ('PENDING', 'RETRYABLE_ERROR') " +
            "AND (work.nextEligibleAtMillis IS NULL OR work.nextEligibleAtMillis <= :nowMillis) " +
            "AND asset.availability = 'AVAILABLE' " +
            "AND asset.sourceFingerprint = work.sourceFingerprint " +
            "AND access.processAccessRevision = work.accessRevision " +
            "AND access.accessScope != 'None' " +
            "ORDER BY work.assetId LIMIT :limit",
    )
    suspend fun eligibleWork(channel: String, nowMillis: Long, limit: Int): List<IndexChannelWorkEntity>

    @Query(
        "UPDATE index_channel_work SET state = 'RUNNING', attempt = attempt + 1, " +
            "leaseToken = :leaseToken, leaseExpiresAtMillis = :leaseExpiresAtMillis, " +
            "executionWindowId = :windowId, nextEligibleAtMillis = NULL, errorCode = NULL, " +
            "updatedAtMillis = :nowMillis " +
            "WHERE assetId = :assetId AND channel = :channel " +
            "AND sourceFingerprint = :sourceFingerprint " +
            "AND state IN ('PENDING', 'RETRYABLE_ERROR') " +
            "AND (nextEligibleAtMillis IS NULL OR nextEligibleAtMillis <= :nowMillis)",
    )
    suspend fun claimWorkRow(
        assetId: Long,
        channel: String,
        sourceFingerprint: String,
        leaseToken: String,
        leaseExpiresAtMillis: Long,
        windowId: String,
        nowMillis: Long,
    ): Int

    @Query(
        "UPDATE index_channel_work SET state = 'PENDING', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, publicationToken = NULL, stagedArtifactPath = NULL, " +
            "stagedArtifactLength = NULL, stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, " +
            "errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE state = 'RUNNING' AND leaseExpiresAtMillis <= :nowMillis",
    )
    suspend fun releaseExpiredWork(nowMillis: Long): Int

    @Query(
        "UPDATE index_channel_work SET state = 'PENDING', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, publicationToken = NULL, stagedArtifactPath = NULL, " +
            "stagedArtifactLength = NULL, stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, " +
            "errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE executionWindowId = :windowId AND state = 'RUNNING'",
    )
    suspend fun releaseWindowWork(windowId: String, nowMillis: Long): Int

    @Upsert
    suspend fun replacePublication(publication: IndexChannelPublicationEntity)

    @Query("SELECT * FROM index_channel_publication WHERE assetId = :assetId AND channel = :channel")
    suspend fun publication(assetId: Long, channel: String): IndexChannelPublicationEntity?

    @Query("SELECT * FROM index_channel_publication WHERE publicationToken = :publicationToken")
    suspend fun publicationByToken(publicationToken: String): IndexChannelPublicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePublicationClock(clock: IndexPublicationClockEntity)

    @Query("SELECT * FROM index_publication_clock WHERE singletonId = 1")
    suspend fun publicationClock(): IndexPublicationClockEntity?

    @Query(
        "UPDATE index_channel_work SET state = 'DONE', publicationToken = :publicationToken, " +
            "leaseToken = NULL, leaseExpiresAtMillis = NULL, executionWindowId = NULL, " +
            "nextEligibleAtMillis = NULL, errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE assetId = :assetId AND channel = :channel AND state = 'RUNNING' " +
            "AND leaseToken = :leaseToken AND sourceFingerprint = :sourceFingerprint " +
            "AND accessRevision = :accessRevision AND pipelineVersion = :pipelineVersion " +
            "AND componentHash = :componentHash",
    )
    suspend fun completeSqlWorkRow(
        assetId: Long,
        channel: String,
        leaseToken: String,
        publicationToken: String,
        sourceFingerprint: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
        nowMillis: Long,
    ): Int

    @Query(
        "UPDATE index_channel_work SET state = :state, leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, nextEligibleAtMillis = :nextEligibleAtMillis, errorCode = :errorCode, " +
            "updatedAtMillis = :nowMillis " +
            "WHERE leaseToken = :leaseToken AND state = 'RUNNING'",
    )
    suspend fun failWorkRow(
        leaseToken: String,
        state: String,
        nextEligibleAtMillis: Long?,
        errorCode: String,
        nowMillis: Long,
    ): Int

    @Query("SELECT state, COUNT(*) AS count FROM index_channel_work GROUP BY state ORDER BY state")
    suspend fun workStateCounts(): List<IndexWorkStateCount>

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun catalogAsset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun currentAccessObservation(): CatalogAccessObservationEntity?

    @Transaction
    suspend fun ensureWork(
        assetId: Long,
        channel: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
        nowMillis: Long,
    ): IndexChannelWorkEntity {
        require(channel in IndexChannel.all)
        require(accessRevision > 0)
        require(contractValue(pipelineVersion))
        require(Sha256.matches(componentHash))
        val asset = checkNotNull(catalogAsset(assetId))
        check(asset.availability == CatalogAvailability.AVAILABLE)
        val access = checkNotNull(currentAccessObservation())
        check(access.processAccessRevision == accessRevision && access.accessScope != "None")
        val current = work(assetId, channel)
        if (
            current != null &&
            current.sourceFingerprint == asset.sourceFingerprint &&
            current.accessRevision == accessRevision &&
            current.pipelineVersion == pipelineVersion &&
            current.componentHash == componentHash
        ) {
            return current
        }
        val pending =
            IndexChannelWorkEntity(
                assetId = assetId,
                channel = channel,
                state = IndexWorkState.PENDING,
                sourceFingerprint = asset.sourceFingerprint,
                accessRevision = accessRevision,
                pipelineVersion = pipelineVersion,
                componentHash = componentHash,
                attempt = 0,
                leaseToken = null,
                leaseExpiresAtMillis = null,
                executionWindowId = null,
                publicationToken = null,
                stagedArtifactPath = null,
                stagedArtifactLength = null,
                stagedArtifactSha256 = null,
                nextEligibleAtMillis = null,
                errorCode = null,
                updatedAtMillis = nowMillis,
            )
        replaceWork(pending)
        return pending
    }

    @Transaction
    suspend fun startExecutionWindow(window: IndexExecutionWindowEntity, nowMillis: Long) {
        require(identifier(window.windowId))
        require(identifier(window.leaseToken))
        require(window.state == IndexExecutionWindowState.RUNNING)
        require(window.startedAtMillis == nowMillis)
        require(window.expiresAtMillis > nowMillis)
        val operation = checkNotNull(operation(window.operationId))
        check(
            operation.state in
                setOf(
                    IndexOperationState.PLANNED,
                    IndexOperationState.RUNNING,
                    IndexOperationState.PAUSED_CONSTRAINT,
                    IndexOperationState.WAITING_SYSTEM,
                ),
        )
        expireExecutionWindows(nowMillis)
        releaseExpiredWork(nowMillis)
        check(liveExecutionWindowCount(window.operationId, nowMillis) == 0)
        check(updateOperationState(window.operationId, IndexOperationState.RUNNING, nowMillis) == 1)
        insertExecutionWindow(window)
    }

    @Transaction
    suspend fun claimBatch(
        windowId: String,
        channel: String,
        claimNonce: String,
        nowMillis: Long,
        leaseDurationMillis: Long,
        limit: Int,
    ): List<IndexChannelWorkEntity> {
        require(channel in IndexChannel.all)
        require(claimNonce.length <= MaximumClaimNonceLength && identifier(claimNonce))
        require(leaseDurationMillis in 1..MaximumLeaseDurationMillis)
        require(limit in 1..MaximumBatchSize)
        val window = checkNotNull(executionWindow(windowId))
        check(window.state == IndexExecutionWindowState.RUNNING && window.expiresAtMillis > nowMillis)
        check(operation(window.operationId)?.state == IndexOperationState.RUNNING)
        val expiry = minOf(window.expiresAtMillis, Math.addExact(nowMillis, leaseDurationMillis))
        val claimed = mutableListOf<IndexChannelWorkEntity>()
        eligibleWork(channel, nowMillis, limit).forEach { candidate ->
            val token = "$claimNonce:${candidate.assetId}:${candidate.channel}"
            if (
                claimWorkRow(
                    assetId = candidate.assetId,
                    channel = candidate.channel,
                    sourceFingerprint = candidate.sourceFingerprint,
                    leaseToken = token,
                    leaseExpiresAtMillis = expiry,
                    windowId = windowId,
                    nowMillis = nowMillis,
                ) == 1
            ) {
                claimed += checkNotNull(work(candidate.assetId, candidate.channel))
            }
        }
        return claimed
    }

    @Transaction
    suspend fun commitSqlPublication(
        leaseToken: String,
        publicationToken: String,
        resultSha256: String,
        resultLength: Long,
        nowMillis: Long,
    ): IndexChannelPublicationEntity? {
        require(identifier(publicationToken))
        require(Sha256.matches(resultSha256))
        require(resultLength >= 0)
        val work = workByLease(leaseToken) ?: return null
        if (work.state != IndexWorkState.RUNNING || (work.leaseExpiresAtMillis ?: 0) <= nowMillis) return null
        val asset = catalogAsset(work.assetId) ?: return null
        val access = currentAccessObservation() ?: return null
        if (
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != work.sourceFingerprint ||
            access.processAccessRevision != work.accessRevision ||
            access.accessScope == "None"
        ) {
            return null
        }
        val epoch = Math.addExact(publicationClock()?.lastEpoch ?: 0, 1)
        val publication =
            IndexChannelPublicationEntity(
                assetId = work.assetId,
                channel = work.channel,
                publicationToken = publicationToken,
                sourceFingerprint = work.sourceFingerprint,
                accessRevision = work.accessRevision,
                pipelineVersion = work.pipelineVersion,
                componentHash = work.componentHash,
                resultSha256 = resultSha256,
                resultLength = resultLength,
                publicationEpoch = epoch,
                publishedAtMillis = nowMillis,
            )
        val tokenOwner = publicationByToken(publicationToken)
        if (tokenOwner != null && (tokenOwner.assetId != work.assetId || tokenOwner.channel != work.channel)) {
            return null
        }
        replacePublication(publication)
        check(publication(work.assetId, work.channel) == publication)
        check(
            completeSqlWorkRow(
                assetId = work.assetId,
                channel = work.channel,
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
    suspend fun recordFailure(
        leaseToken: String,
        errorCode: String,
        nextEligibleAtMillis: Long,
        nowMillis: Long,
        maximumAttempts: Int = 3,
    ): String? {
        require(ErrorCode.matches(errorCode))
        require(nextEligibleAtMillis >= nowMillis)
        require(maximumAttempts in 1..10)
        val work = workByLease(leaseToken) ?: return null
        if (work.state != IndexWorkState.RUNNING || (work.leaseExpiresAtMillis ?: 0) <= nowMillis) return null
        val terminal = work.attempt >= maximumAttempts
        val state = if (terminal) IndexWorkState.PERMANENT_ERROR else IndexWorkState.RETRYABLE_ERROR
        val retryAt = if (terminal) null else nextEligibleAtMillis
        if (failWorkRow(leaseToken, state, retryAt, errorCode, nowMillis) != 1) return null
        return state
    }

    @Transaction
    suspend fun stopExecutionWindow(windowId: String, state: String, nowMillis: Long): Int {
        require(state in setOf(IndexExecutionWindowState.FINISHED, IndexExecutionWindowState.EXPIRED, IndexExecutionWindowState.CANCELLED))
        val stopped = stopExecutionWindowRow(windowId, state, nowMillis)
        if (stopped == 1) releaseWindowWork(windowId, nowMillis)
        return stopped
    }

    @Transaction
    suspend fun recoverExpiredExecution(nowMillis: Long): Pair<Int, Int> {
        val windows = expireExecutionWindows(nowMillis)
        val work = releaseExpiredWork(nowMillis)
        return windows to work
    }

    private fun identifier(value: String): Boolean = value.length in 1..96 && Identifier.matches(value)

    private fun contractValue(value: String): Boolean = value.length in 1..128 && ContractValue.matches(value)

    companion object {
        private val Identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]*")
        private val Sha256 = Regex("[0-9a-f]{64}")
        private val ErrorCode = Regex("[A-Z][A-Z0-9_]{0,63}")
        const val MaximumLeaseDurationMillis = 15 * 60 * 1_000L
        const val MaximumBatchSize = 256
        const val MaximumClaimNonceLength = 48
    }
}
