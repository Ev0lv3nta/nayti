package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "catalog_asset",
    indices = [Index(value = ["volumeName", "mediaStoreId"], unique = true)],
)
data class CatalogAssetEntity(
    @PrimaryKey(autoGenerate = true) val assetId: Long = 0,
    val volumeName: String,
    val mediaStoreId: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val generationAdded: Long,
    val generationModified: Long,
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long?,
    val displayName: String?,
    val bucketId: Long?,
    val bucketDisplayName: String?,
    val relativePath: String?,
    val sourceFingerprint: String,
    val availability: String,
    val lastSeenInventoryRunId: Long,
    val missingFullObservationCount: Int,
    val quarantineStartedAtMillis: Long?,
    val sourceObservedAtMillis: Long,
    val derivedDataPurgedAtMillis: Long? = null,
)

@Entity(tableName = "catalog_volume")
data class CatalogVolumeEntity(
    @PrimaryKey val volumeName: String,
    val mediaStoreVersion: String,
    val generationWatermark: Long,
    val isMounted: Boolean,
    val dirty: Boolean,
    val lastSuccessfulInventoryRunId: Long?,
    val lastFullInventoryAtMillis: Long?,
)

@Entity(
    tableName = "catalog_inventory_run",
    indices = [Index(value = ["token"], unique = true)],
)
data class CatalogInventoryRunEntity(
    @PrimaryKey(autoGenerate = true) val runId: Long = 0,
    val token: String,
    val accessScope: String,
    val accessRevision: Long,
    val mode: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val status: String,
    val observedAssetCount: Int,
)

@Entity(tableName = "catalog_access_observation")
data class CatalogAccessObservationEntity(
    @PrimaryKey val singletonId: Int = 1,
    val accessScope: String,
    val processAccessRevision: Long,
    val observationSequence: Long,
    val observedAtMillis: Long,
)

@Entity(tableName = "catalog_watermark")
data class CatalogWatermarkEntity(
    @PrimaryKey val singletonId: Int = 1,
    val catalogRevision: Long,
    val lastSuccessfulInventoryRunId: Long?,
    val updatedAtMillis: Long,
)

data class CatalogAssetDraft(
    val volumeName: String,
    val mediaStoreId: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val generationAdded: Long,
    val generationModified: Long,
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long?,
    val displayName: String?,
    val bucketId: Long?,
    val bucketDisplayName: String?,
    val relativePath: String?,
    val sourceFingerprint: String,
    val isPending: Boolean,
    val isTrashed: Boolean,
)

data class CatalogCounts(
    val total: Long,
    val available: Long,
    val outOfScope: Long,
    val offline: Long,
    val pending: Long,
    val trashed: Long,
    val missing: Long,
    val deleted: Long,
    val retainedQuarantine: Long,
)

data class SearchAlbumFacet(
    val bucketId: Long,
    val displayName: String,
    val assetCount: Long,
)

data class SearchMimeFacet(
    val mimeType: String,
    val assetCount: Long,
)

data class SearchFilterFacets(
    val albums: List<SearchAlbumFacet>,
    val mimeTypes: List<SearchMimeFacet>,
)

object CatalogAvailability {
    const val AVAILABLE = "AVAILABLE"
    const val OUT_OF_SCOPE = "OUT_OF_SCOPE"
    const val VOLUME_OFFLINE = "VOLUME_OFFLINE"
    const val PENDING = "PENDING"
    const val TRASHED = "TRASHED"
    const val MISSING_UNCONFIRMED = "MISSING_UNCONFIRMED"
    const val DELETED = "DELETED"
}

object CatalogInventoryStatus {
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val ABANDONED = "ABANDONED"
    const val FAILED = "FAILED"
}

object CatalogInventoryMode {
    const val FULL = "FULL"
    const val INCREMENTAL = "INCREMENTAL"
    const val ACCESS_ONLY = "ACCESS_ONLY"
}
