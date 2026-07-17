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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperationChannels(channels: List<IndexOperationChannelEntity>)

    @Query("SELECT * FROM index_operation_channel WHERE operationId = :operationId ORDER BY priority")
    suspend fun operationChannels(operationId: String): List<IndexOperationChannelEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperationAssets(assets: List<IndexOperationAssetEntity>)

    @Query("SELECT * FROM index_operation_asset WHERE operationId = :operationId ORDER BY assetId")
    suspend fun operationAssets(operationId: String): List<IndexOperationAssetEntity>

    @Query("UPDATE index_operation SET state = :state, updatedAtMillis = :nowMillis WHERE operationId = :operationId")
    suspend fun updateOperationState(operationId: String, state: String, nowMillis: Long): Int

    @Query(
        "UPDATE index_operation SET state = :state, autoResume = :autoResume, " +
            "updatedAtMillis = :nowMillis, " +
            "completedAtMillis = CASE WHEN :state = 'CANCELLED' THEN :nowMillis ELSE NULL END " +
            "WHERE operationId = :operationId",
    )
    suspend fun updateOperationControlRow(
        operationId: String,
        state: String,
        autoResume: Boolean,
        nowMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecutionWindow(window: IndexExecutionWindowEntity)

    @Query("SELECT * FROM index_execution_window WHERE windowId = :windowId")
    suspend fun executionWindow(windowId: String): IndexExecutionWindowEntity?

    @Query(
        "SELECT * FROM index_execution_window " +
            "WHERE operationId = :operationId AND state = 'RUNNING' ORDER BY startedAtMillis, windowId",
    )
    suspend fun runningExecutionWindows(operationId: String): List<IndexExecutionWindowEntity>

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
            "AND (work.channel != 'OCR_SEMANTIC' OR EXISTS (" +
            "SELECT 1 FROM index_channel_work AS dependency " +
            "WHERE dependency.assetId = work.assetId AND dependency.channel = 'OCR' " +
            "AND dependency.state = 'DONE' " +
            "AND dependency.sourceFingerprint = work.sourceFingerprint" +
            ")) " +
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

    @Query(
        "SELECT :operationId AS operationId, " +
            "(SELECT COUNT(*) FROM index_operation_asset WHERE operationId = :operationId) * " +
            "(SELECT COUNT(*) FROM index_operation_channel WHERE operationId = :operationId) AS plannedCount, " +
            "(SELECT COUNT(*) FROM index_operation_asset AS asset " +
            "CROSS JOIN index_operation_channel AS channel " +
            "INNER JOIN index_channel_work AS work ON work.assetId = asset.assetId AND work.channel = channel.channel " +
            "WHERE asset.operationId = :operationId AND channel.operationId = :operationId " +
            "AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.pipelineVersion = channel.pipelineVersion AND work.componentHash = channel.componentHash " +
            "AND work.state = 'DONE') AS committedCount, " +
            "(SELECT COUNT(*) FROM index_operation_asset AS asset " +
            "CROSS JOIN index_operation_channel AS channel " +
            "INNER JOIN index_channel_work AS work ON work.assetId = asset.assetId AND work.channel = channel.channel " +
            "WHERE asset.operationId = :operationId AND channel.operationId = :operationId " +
            "AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.pipelineVersion = channel.pipelineVersion AND work.componentHash = channel.componentHash " +
            "AND work.state = 'PERMANENT_ERROR') AS permanentGapCount, " +
            "((SELECT COUNT(*) FROM index_operation_asset WHERE operationId = :operationId) * " +
            "(SELECT COUNT(*) FROM index_operation_channel WHERE operationId = :operationId) - " +
            "(SELECT COUNT(*) FROM index_operation_asset AS asset " +
            "CROSS JOIN index_operation_channel AS channel " +
            "INNER JOIN index_channel_work AS work ON work.assetId = asset.assetId AND work.channel = channel.channel " +
            "WHERE asset.operationId = :operationId AND channel.operationId = :operationId " +
            "AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.pipelineVersion = channel.pipelineVersion AND work.componentHash = channel.componentHash " +
            "AND work.state IN ('DONE', 'PERMANENT_ERROR'))) AS outstandingCount",
    )
    suspend fun operationProgress(operationId: String): IndexOperationProgress

    @Query(
        "SELECT :channel AS channel, " +
            "(SELECT COUNT(*) FROM catalog_asset WHERE availability = 'AVAILABLE') AS accessibleAssetCount, " +
            "(SELECT COUNT(*) FROM catalog_asset AS asset INNER JOIN index_channel_work AS work " +
            "ON work.assetId = asset.assetId AND work.channel = :channel " +
            "WHERE asset.availability = 'AVAILABLE' AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.accessRevision = :accessRevision AND work.pipelineVersion = :pipelineVersion " +
            "AND work.componentHash = :componentHash AND work.state = 'DONE') AS committedAssetCount, " +
            "(SELECT COUNT(*) FROM catalog_asset AS asset INNER JOIN index_channel_work AS work " +
            "ON work.assetId = asset.assetId AND work.channel = :channel " +
            "WHERE asset.availability = 'AVAILABLE' AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.accessRevision = :accessRevision AND work.pipelineVersion = :pipelineVersion " +
            "AND work.componentHash = :componentHash AND work.state = 'PERMANENT_ERROR') AS permanentGapCount, " +
            "((SELECT COUNT(*) FROM catalog_asset WHERE availability = 'AVAILABLE') - " +
            "(SELECT COUNT(*) FROM catalog_asset AS asset INNER JOIN index_channel_work AS work " +
            "ON work.assetId = asset.assetId AND work.channel = :channel " +
            "WHERE asset.availability = 'AVAILABLE' AND work.sourceFingerprint = asset.sourceFingerprint " +
            "AND work.accessRevision = :accessRevision AND work.pipelineVersion = :pipelineVersion " +
            "AND work.componentHash = :componentHash AND work.state IN ('DONE', 'PERMANENT_ERROR'))) " +
            "AS outstandingAssetCount",
    )
    suspend fun channelCoverageRow(
        channel: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
    ): IndexChannelCoverage

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertErrorIfAbsent(error: IndexErrorLedgerEntity): Long

    @Query("SELECT * FROM index_error_ledger WHERE errorKey = :errorKey")
    suspend fun ledgerError(errorKey: String): IndexErrorLedgerEntity?

    @Query(
        "UPDATE index_error_ledger SET occurrenceCount = occurrenceCount + 1, " +
            "lastSeenAtMillis = :nowMillis, retryable = retryable AND :retryable, resolvedAtMillis = NULL " +
            "WHERE errorKey = :errorKey",
    )
    suspend fun bumpLedgerError(errorKey: String, retryable: Boolean, nowMillis: Long): Int

    @Query(
        "UPDATE index_error_ledger SET resolvedAtMillis = :nowMillis " +
            "WHERE assetId = :assetId AND channel = :channel AND resolvedAtMillis IS NULL",
    )
    suspend fun resolveItemErrors(assetId: Long, channel: String, nowMillis: Long): Int

    @Query(
        "UPDATE index_operation SET state = :state, updatedAtMillis = :nowMillis, completedAtMillis = :nowMillis " +
            "WHERE operationId = :operationId AND state NOT IN ('CANCELLED', 'COMPLETED', 'COMPLETED_WITH_GAPS')",
    )
    suspend fun completeOperationRow(operationId: String, state: String, nowMillis: Long): Int

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun catalogAsset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun currentAccessObservation(): CatalogAccessObservationEntity?

    @Transaction
    suspend fun createOperation(
        operation: IndexOperationEntity,
        channels: List<IndexOperationChannelEntity>,
        assets: List<IndexOperationAssetEntity>,
    ) {
        require(identifier(operation.operationId))
        require(identifier(operation.profileId))
        require(identifier(operation.targetPackId))
        require(contractValue(operation.targetPackVersion))
        require(operation.denominatorCatalogRevision >= 0)
        require(operation.denominatorAssetCount >= 0)
        require(operation.state == IndexOperationState.PLANNED)
        require(channels.isNotEmpty() && channels.size <= IndexChannel.all.size)
        require(channels.all { channel ->
            channel.operationId == operation.operationId &&
                channel.channel in IndexChannel.all &&
                channel.priority >= 0 &&
                contractValue(channel.pipelineVersion) &&
                Sha256.matches(channel.componentHash)
        })
        require(channels.map(IndexOperationChannelEntity::channel).toSet().size == channels.size)
        require(channels.map(IndexOperationChannelEntity::priority).toSet().size == channels.size)
        require(assets.size.toLong() == operation.denominatorAssetCount)
        require(assets.all { asset ->
            asset.operationId == operation.operationId &&
                asset.assetId > 0 &&
                asset.sourceFingerprint.isNotBlank() &&
                asset.sourceFingerprint.length <= 128
        })
        require(assets.map(IndexOperationAssetEntity::assetId).toSet().size == assets.size)
        insertOperation(operation)
        insertOperationChannels(channels)
        if (assets.isNotEmpty()) insertOperationAssets(assets)
    }

    @Transaction
    suspend fun ensureWorkBatch(
        assetIds: List<Long>,
        channel: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
        nowMillis: Long,
    ): Int {
        require(assetIds.isNotEmpty() && assetIds.size <= MaximumBatchSize)
        assetIds.forEach { assetId ->
            ensureWork(assetId, channel, accessRevision, pipelineVersion, componentHash, nowMillis)
        }
        return assetIds.size
    }

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
        check(operation.state != IndexOperationState.WAITING_SYSTEM || operation.autoResume) {
            "Operation is stopped until an explicit resume"
        }
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
    suspend fun transitionOperation(
        operationId: String,
        state: String,
        autoResume: Boolean,
        nowMillis: Long,
    ): IndexOperationEntity {
        require(state in ControllableOperationStates)
        require(
            when (state) {
                IndexOperationState.PLANNED,
                IndexOperationState.PAUSED_CONSTRAINT,
                -> autoResume
                IndexOperationState.PAUSED_USER,
                IndexOperationState.CANCELLED,
                -> !autoResume
                IndexOperationState.WAITING_SYSTEM -> true
                else -> false
            },
        )
        val current = checkNotNull(operation(operationId))
        if (current.state == state && current.autoResume == autoResume) return current
        val waitingPolicyChange = current.state == IndexOperationState.WAITING_SYSTEM && state == current.state
        check(waitingPolicyChange || state in allowedControlTargets(current.state)) {
            "Invalid operation transition: ${current.state} -> $state"
        }
        if (state != IndexOperationState.PLANNED) {
            runningExecutionWindows(operationId).forEach { window ->
                if (stopExecutionWindowRow(window.windowId, IndexExecutionWindowState.CANCELLED, nowMillis) == 1) {
                    releaseWindowWork(window.windowId, nowMillis)
                }
            }
        }
        check(updateOperationControlRow(operationId, state, autoResume, nowMillis) == 1)
        return checkNotNull(operation(operationId))
    }

    @Transaction
    suspend fun recoverExpiredExecution(nowMillis: Long): Pair<Int, Int> {
        val windows = expireExecutionWindows(nowMillis)
        val work = releaseExpiredWork(nowMillis)
        return windows to work
    }

    @Transaction
    suspend fun recordLedgerError(error: IndexErrorLedgerEntity) {
        require(error.errorKey.length in 1..240 && ErrorKey.matches(error.errorKey))
        require(error.scope in setOf(IndexErrorScope.ITEM, IndexErrorScope.OPERATION, IndexErrorScope.PROCESS))
        require(error.operationId == null || identifier(error.operationId))
        require(error.executionWindowId == null || identifier(error.executionWindowId))
        require(error.assetId == null || error.assetId > 0)
        require(error.channel == null || error.channel in IndexChannel.all)
        require(ErrorCode.matches(error.code))
        require(error.occurrenceCount == 1)
        require(error.firstSeenAtMillis == error.lastSeenAtMillis && error.resolvedAtMillis == null)
        require(
            error.scope != IndexErrorScope.ITEM ||
                (error.operationId != null && error.assetId != null && error.channel != null),
        )
        val inserted = insertErrorIfAbsent(error)
        if (inserted == -1L) {
            val current = checkNotNull(ledgerError(error.errorKey))
            check(
                current.copy(
                    retryable = error.retryable,
                    occurrenceCount = 1,
                    firstSeenAtMillis = error.firstSeenAtMillis,
                    lastSeenAtMillis = error.lastSeenAtMillis,
                    resolvedAtMillis = null,
                ) == error,
            )
            check(bumpLedgerError(error.errorKey, error.retryable, error.lastSeenAtMillis) == 1)
        }
    }

    @Transaction
    suspend fun refreshOperationTerminalState(operationId: String, nowMillis: Long): IndexOperationProgress {
        val operation = checkNotNull(operation(operationId))
        val progress = operationProgress(operationId)
        check(progress.plannedCount >= 0)
        check(progress.committedCount >= 0 && progress.permanentGapCount >= 0 && progress.outstandingCount >= 0)
        check(progress.committedCount + progress.permanentGapCount + progress.outstandingCount == progress.plannedCount)
        if (
            progress.outstandingCount == 0L &&
            operation.state !in setOf(IndexOperationState.CANCELLED, IndexOperationState.COMPLETED, IndexOperationState.COMPLETED_WITH_GAPS)
        ) {
            val terminal =
                if (progress.permanentGapCount == 0L) {
                    IndexOperationState.COMPLETED
                } else {
                    IndexOperationState.COMPLETED_WITH_GAPS
                }
            check(completeOperationRow(operationId, terminal, nowMillis) == 1)
        }
        return progress
    }

    @Transaction
    suspend fun channelCoverage(
        channel: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
    ): IndexChannelCoverage {
        require(channel in IndexChannel.all)
        require(accessRevision > 0)
        require(contractValue(pipelineVersion))
        require(Sha256.matches(componentHash))
        val coverage = channelCoverageRow(channel, accessRevision, pipelineVersion, componentHash)
        check(coverage.accessibleAssetCount >= 0)
        check(coverage.committedAssetCount >= 0 && coverage.permanentGapCount >= 0 && coverage.outstandingAssetCount >= 0)
        check(
            coverage.committedAssetCount + coverage.permanentGapCount + coverage.outstandingAssetCount ==
                coverage.accessibleAssetCount,
        )
        return coverage
    }

    private fun identifier(value: String): Boolean = value.length in 1..96 && Identifier.matches(value)

    private fun contractValue(value: String): Boolean = value.length in 1..128 && ContractValue.matches(value)

    private fun allowedControlTargets(state: String): Set<String> =
        when (state) {
            IndexOperationState.PLANNED,
            IndexOperationState.RUNNING,
            -> setOf(
                IndexOperationState.PAUSED_USER,
                IndexOperationState.PAUSED_CONSTRAINT,
                IndexOperationState.WAITING_SYSTEM,
                IndexOperationState.CANCELLED,
            )
            IndexOperationState.PAUSED_USER -> setOf(IndexOperationState.PLANNED, IndexOperationState.CANCELLED)
            IndexOperationState.PAUSED_CONSTRAINT ->
                setOf(
                    IndexOperationState.PLANNED,
                    IndexOperationState.PAUSED_USER,
                    IndexOperationState.WAITING_SYSTEM,
                    IndexOperationState.CANCELLED,
                )
            IndexOperationState.WAITING_SYSTEM ->
                setOf(
                    IndexOperationState.PLANNED,
                    IndexOperationState.PAUSED_USER,
                    IndexOperationState.PAUSED_CONSTRAINT,
                    IndexOperationState.CANCELLED,
                )
            else -> emptySet()
        }

    companion object {
        private val Identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]*")
        private val Sha256 = Regex("[0-9a-f]{64}")
        private val ErrorCode = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val ErrorKey = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val ControllableOperationStates =
            setOf(
                IndexOperationState.PLANNED,
                IndexOperationState.PAUSED_USER,
                IndexOperationState.PAUSED_CONSTRAINT,
                IndexOperationState.WAITING_SYSTEM,
                IndexOperationState.CANCELLED,
            )
        const val MaximumLeaseDurationMillis = 15 * 60 * 1_000L
        const val MaximumBatchSize = 256
        const val MaximumClaimNonceLength = 48
    }
}
