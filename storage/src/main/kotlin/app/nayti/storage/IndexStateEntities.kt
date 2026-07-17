package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index

@Entity(tableName = "index_operation", primaryKeys = ["operationId"])
data class IndexOperationEntity(
    val operationId: String,
    val profileId: String,
    val targetPackId: String,
    val targetPackVersion: String,
    val denominatorCatalogRevision: Long,
    val denominatorAssetCount: Long,
    val state: String,
    val autoResume: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long?,
)

@Entity(
    tableName = "index_operation_channel",
    primaryKeys = ["operationId", "channel"],
    indices = [Index(value = ["operationId", "priority"], unique = true)],
)
data class IndexOperationChannelEntity(
    val operationId: String,
    val channel: String,
    val priority: Int,
    val pipelineVersion: String,
    val componentHash: String,
)

@Entity(
    tableName = "index_operation_asset",
    primaryKeys = ["operationId", "assetId"],
    indices = [Index(value = ["assetId"])],
)
data class IndexOperationAssetEntity(
    val operationId: String,
    val assetId: Long,
    val sourceFingerprint: String,
)

@Entity(
    tableName = "index_execution_window",
    primaryKeys = ["windowId"],
    indices = [
        Index(value = ["operationId", "state"]),
        Index(value = ["leaseToken"], unique = true),
    ],
)
data class IndexExecutionWindowEntity(
    val windowId: String,
    val operationId: String,
    val hostType: String,
    val leaseToken: String,
    val state: String,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
    val finishedAtMillis: Long?,
)

@Entity(
    tableName = "index_channel_work",
    primaryKeys = ["assetId", "channel"],
    indices = [
        Index(value = ["channel", "state", "nextEligibleAtMillis"]),
        Index(value = ["leaseToken"], unique = true),
        Index(value = ["executionWindowId"]),
        Index(value = ["publicationToken"]),
    ],
)
data class IndexChannelWorkEntity(
    val assetId: Long,
    val channel: String,
    val state: String,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val attempt: Int,
    val leaseToken: String?,
    val leaseExpiresAtMillis: Long?,
    val executionWindowId: String?,
    val publicationToken: String?,
    val stagedArtifactPath: String?,
    val stagedArtifactLength: Long?,
    val stagedArtifactSha256: String?,
    val nextEligibleAtMillis: Long?,
    val errorCode: String?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "index_channel_publication",
    primaryKeys = ["assetId", "channel"],
    indices = [Index(value = ["publicationToken"], unique = true)],
)
data class IndexChannelPublicationEntity(
    val assetId: Long,
    val channel: String,
    val publicationToken: String,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val resultSha256: String,
    val resultLength: Long,
    val publicationEpoch: Long,
    val publishedAtMillis: Long,
)

@Entity(tableName = "index_publication_clock", primaryKeys = ["singletonId"])
data class IndexPublicationClockEntity(
    val singletonId: Int = 1,
    val lastEpoch: Long,
)

data class IndexWorkStateCount(
    val state: String,
    val count: Long,
)

@Entity(
    tableName = "index_error_ledger",
    primaryKeys = ["errorKey"],
    indices = [
        Index(value = ["operationId", "resolvedAtMillis"]),
        Index(value = ["assetId", "channel", "resolvedAtMillis"]),
        Index(value = ["scope", "code"]),
    ],
)
data class IndexErrorLedgerEntity(
    val errorKey: String,
    val scope: String,
    val operationId: String?,
    val executionWindowId: String?,
    val assetId: Long?,
    val channel: String?,
    val code: String,
    val retryable: Boolean,
    val occurrenceCount: Int,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val resolvedAtMillis: Long?,
)

data class IndexOperationProgress(
    val operationId: String,
    val plannedCount: Long,
    val committedCount: Long,
    val permanentGapCount: Long,
    val outstandingCount: Long,
)

data class IndexChannelCoverage(
    val channel: String,
    val accessibleAssetCount: Long,
    val committedAssetCount: Long,
    val permanentGapCount: Long,
    val outstandingAssetCount: Long,
)

object IndexErrorScope {
    const val ITEM = "ITEM"
    const val OPERATION = "OPERATION"
    const val PROCESS = "PROCESS"
}

object IndexChannel {
    const val OCR = "OCR"
    const val OCR_SEMANTIC = "OCR_SEMANTIC"
    const val VISUAL = "VISUAL"
    const val PHASH = "PHASH"

    val all: Set<String> = setOf(OCR, OCR_SEMANTIC, VISUAL, PHASH)
}

object IndexWorkState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val STAGED = "STAGED"
    const val DONE = "DONE"
    const val RETRYABLE_ERROR = "RETRYABLE_ERROR"
    const val PERMANENT_ERROR = "PERMANENT_ERROR"
}

object IndexOperationState {
    const val PLANNED = "PLANNED"
    const val RUNNING = "RUNNING"
    const val PAUSED_USER = "PAUSED_USER"
    const val PAUSED_CONSTRAINT = "PAUSED_CONSTRAINT"
    const val WAITING_SYSTEM = "WAITING_SYSTEM"
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_GAPS = "COMPLETED_WITH_GAPS"
    const val CANCELLED = "CANCELLED"
    const val REPAIR_REQUIRED = "REPAIR_REQUIRED"
}

object IndexExecutionWindowState {
    const val RUNNING = "RUNNING"
    const val FINISHED = "FINISHED"
    const val EXPIRED = "EXPIRED"
    const val CANCELLED = "CANCELLED"
}
