package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_asset WHERE volumeName = :volumeName AND mediaStoreId = :mediaStoreId")
    suspend fun asset(volumeName: String, mediaStoreId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun asset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_asset WHERE availability = 'AVAILABLE' ORDER BY assetId")
    suspend fun availableAssets(): List<CatalogAssetEntity>

    @Query("SELECT * FROM catalog_asset ORDER BY assetId")
    suspend fun allAssets(): List<CatalogAssetEntity>

    @Query(
        "SELECT bucketId AS bucketId, COALESCE(NULLIF(TRIM(bucketDisplayName), ''), 'Без названия') AS displayName, " +
            "COUNT(*) AS assetCount FROM catalog_asset WHERE availability = 'AVAILABLE' AND bucketId IS NOT NULL " +
            "GROUP BY bucketId, displayName ORDER BY displayName COLLATE NOCASE, bucketId",
    )
    suspend fun availableAlbumFacets(): List<SearchAlbumFacet>

    @Query(
        "SELECT mimeType AS mimeType, COUNT(*) AS assetCount FROM catalog_asset " +
            "WHERE availability = 'AVAILABLE' GROUP BY mimeType ORDER BY assetCount DESC, mimeType",
    )
    suspend fun availableMimeFacets(): List<SearchMimeFacet>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(asset: CatalogAssetEntity): Long

    @Update
    suspend fun updateAsset(asset: CatalogAssetEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceVolume(volume: CatalogVolumeEntity)

    @Query("SELECT * FROM catalog_volume WHERE volumeName = :volumeName")
    suspend fun volume(volumeName: String): CatalogVolumeEntity?

    @Query("SELECT * FROM catalog_volume ORDER BY volumeName")
    suspend fun volumes(): List<CatalogVolumeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInventoryRun(run: CatalogInventoryRunEntity): Long

    @Query("SELECT * FROM catalog_inventory_run WHERE runId = :runId")
    suspend fun inventoryRun(runId: Long): CatalogInventoryRunEntity?

    @Query(
        "UPDATE catalog_inventory_run SET status = :status, finishedAtMillis = :finishedAtMillis, " +
            "observedAssetCount = :observedAssetCount WHERE runId = :runId AND status = 'RUNNING'",
    )
    suspend fun finishInventoryRun(
        runId: Long,
        status: String,
        finishedAtMillis: Long,
        observedAssetCount: Int,
    ): Int

    @Query("UPDATE catalog_inventory_run SET status = 'ABANDONED', finishedAtMillis = :nowMillis WHERE status = 'RUNNING'")
    suspend fun abandonRunningInventoryRuns(nowMillis: Long): Int

    @Query(
        "UPDATE catalog_inventory_run SET status = :status, finishedAtMillis = :finishedAtMillis " +
            "WHERE runId = :runId AND status = 'RUNNING'",
    )
    suspend fun stopInventoryRun(runId: Long, status: String, finishedAtMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAccessObservation(observation: CatalogAccessObservationEntity)

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun accessObservation(): CatalogAccessObservationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceWatermark(watermark: CatalogWatermarkEntity)

    @Query("SELECT * FROM catalog_watermark WHERE singletonId = 1")
    suspend fun watermark(): CatalogWatermarkEntity?

    @Query(
        "UPDATE catalog_asset SET " +
            "availability = CASE WHEN missingFullObservationCount + 1 >= 2 " +
            "THEN 'DELETED' ELSE 'MISSING_UNCONFIRMED' END, " +
            "missingFullObservationCount = missingFullObservationCount + 1, " +
            "quarantineStartedAtMillis = NULL " +
            "WHERE volumeName = :volumeName AND lastSeenInventoryRunId != :runId " +
            "AND availability != 'DELETED'",
    )
    suspend fun markMissingAfterFullInventory(volumeName: String, runId: Long): Int

    @Query(
        "UPDATE catalog_asset SET availability = 'OUT_OF_SCOPE', missingFullObservationCount = 0, " +
            "quarantineStartedAtMillis = COALESCE(quarantineStartedAtMillis, :nowMillis) " +
            "WHERE volumeName = :volumeName AND lastSeenInventoryRunId != :runId " +
            "AND availability != 'DELETED'",
    )
    suspend fun markUnseenOutOfScope(volumeName: String, runId: Long, nowMillis: Long): Int

    @Query(
        "UPDATE catalog_asset SET availability = 'OUT_OF_SCOPE', missingFullObservationCount = 0, " +
            "quarantineStartedAtMillis = COALESCE(quarantineStartedAtMillis, :nowMillis) " +
            "WHERE availability != 'DELETED'",
    )
    suspend fun markAllOutOfScope(nowMillis: Long): Int

    @Query(
        "UPDATE catalog_asset SET availability = 'VOLUME_OFFLINE', missingFullObservationCount = 0 " +
            "WHERE volumeName = :volumeName AND availability != 'DELETED'",
    )
    suspend fun markVolumeOffline(volumeName: String): Int

    @Query("UPDATE catalog_volume SET dirty = 1")
    suspend fun markAllVolumesDirty(): Int

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN availability = 'AVAILABLE' THEN 1 ELSE 0 END), 0) AS available, " +
            "COALESCE(SUM(CASE WHEN availability = 'OUT_OF_SCOPE' THEN 1 ELSE 0 END), 0) AS outOfScope, " +
            "COALESCE(SUM(CASE WHEN availability = 'VOLUME_OFFLINE' THEN 1 ELSE 0 END), 0) AS offline, " +
            "COALESCE(SUM(CASE WHEN availability = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending, " +
            "COALESCE(SUM(CASE WHEN availability = 'TRASHED' THEN 1 ELSE 0 END), 0) AS trashed, " +
            "COALESCE(SUM(CASE WHEN availability = 'MISSING_UNCONFIRMED' THEN 1 ELSE 0 END), 0) AS missing, " +
            "COALESCE(SUM(CASE WHEN availability = 'DELETED' THEN 1 ELSE 0 END), 0) AS deleted " +
            "FROM catalog_asset",
    )
    suspend fun countsOrNull(): CatalogCounts?

    @Transaction
    suspend fun recordAccessObservation(
        accessScope: String,
        processAccessRevision: Long,
        nowMillis: Long,
    ) {
        val sequence = Math.addExact(accessObservation()?.observationSequence ?: 0, 1)
        replaceAccessObservation(
            CatalogAccessObservationEntity(
                accessScope = accessScope,
                processAccessRevision = processAccessRevision,
                observationSequence = sequence,
                observedAtMillis = nowMillis,
            ),
        )
    }

    @Transaction
    suspend fun applyObservations(
        runId: Long,
        observations: List<CatalogAssetDraft>,
        nowMillis: Long,
    ) {
        observations.forEach { draft ->
            val current = asset(draft.volumeName, draft.mediaStoreId)
            val availability =
                when {
                    draft.isPending -> CatalogAvailability.PENDING
                    draft.isTrashed -> CatalogAvailability.TRASHED
                    else -> CatalogAvailability.AVAILABLE
                }
            val updated =
                CatalogAssetEntity(
                    assetId = current?.assetId ?: 0,
                    volumeName = draft.volumeName,
                    mediaStoreId = draft.mediaStoreId,
                    mimeType = draft.mimeType,
                    sizeBytes = draft.sizeBytes,
                    width = draft.width,
                    height = draft.height,
                    orientationDegrees = draft.orientationDegrees,
                    generationAdded = draft.generationAdded,
                    generationModified = draft.generationModified,
                    dateTakenMillis = draft.dateTakenMillis,
                    dateModifiedSeconds = draft.dateModifiedSeconds,
                    displayName = draft.displayName,
                    bucketId = draft.bucketId,
                    bucketDisplayName = draft.bucketDisplayName,
                    relativePath = draft.relativePath,
                    sourceFingerprint = draft.sourceFingerprint,
                    availability = availability,
                    lastSeenInventoryRunId = runId,
                    missingFullObservationCount = 0,
                    quarantineStartedAtMillis = null,
                    sourceObservedAtMillis = nowMillis,
                )
            if (current == null) {
                check(insertAsset(updated) > 0)
            } else {
                check(updateAsset(updated) == 1)
            }
        }
    }

    @Transaction
    suspend fun completeVolumeInventory(
        runId: Long,
        volumeName: String,
        mediaStoreVersion: String,
        generation: Long,
        accessScope: String,
        isFullInventory: Boolean,
        observedAssetCount: Int,
        nowMillis: Long,
    ) {
        if (isFullInventory) {
            when (accessScope) {
                "Full" -> markMissingAfterFullInventory(volumeName, runId)
                "Selected", "None" -> markUnseenOutOfScope(volumeName, runId, nowMillis)
                else -> error("Unknown access scope: $accessScope")
            }
        }
        val previous = volume(volumeName)
        replaceVolume(
            CatalogVolumeEntity(
                volumeName = volumeName,
                mediaStoreVersion = mediaStoreVersion,
                generationWatermark = generation,
                isMounted = true,
                dirty = false,
                lastSuccessfulInventoryRunId = runId,
                lastFullInventoryAtMillis =
                    if (isFullInventory) nowMillis else previous?.lastFullInventoryAtMillis,
            ),
        )
        check(
            finishInventoryRun(
                runId,
                CatalogInventoryStatus.SUCCEEDED,
                nowMillis,
                observedAssetCount,
            ) == 1,
        )
        val currentWatermark = watermark()
        replaceWatermark(
            CatalogWatermarkEntity(
                catalogRevision = Math.addExact(currentWatermark?.catalogRevision ?: 0, 1),
                lastSuccessfulInventoryRunId = runId,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    @Transaction
    suspend fun setVolumeOffline(volumeName: String, nowMillis: Long) {
        markVolumeOffline(volumeName)
        val previous = volume(volumeName) ?: return
        replaceVolume(previous.copy(isMounted = false, dirty = true))
        val currentWatermark = watermark()
        replaceWatermark(
            CatalogWatermarkEntity(
                catalogRevision = Math.addExact(currentWatermark?.catalogRevision ?: 0, 1),
                lastSuccessfulInventoryRunId = currentWatermark?.lastSuccessfulInventoryRunId,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    @Transaction
    suspend fun applyNoAccess(nowMillis: Long) {
        markAllOutOfScope(nowMillis)
        val currentWatermark = watermark()
        replaceWatermark(
            CatalogWatermarkEntity(
                catalogRevision = Math.addExact(currentWatermark?.catalogRevision ?: 0, 1),
                lastSuccessfulInventoryRunId = currentWatermark?.lastSuccessfulInventoryRunId,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    suspend fun counts(): CatalogCounts =
        countsOrNull()
            ?: CatalogCounts(0, 0, 0, 0, 0, 0, 0, 0)

    @Transaction
    suspend fun searchFilterFacets(): SearchFilterFacets =
        SearchFilterFacets(
            albums = availableAlbumFacets(),
            mimeTypes = availableMimeFacets(),
        )
}
