package app.nayti.indexer

import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.AccessRevisionGate
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaObservation
import app.nayti.platform.media.MediaStoreGateway
import app.nayti.platform.media.MediaVolumeSnapshot
import app.nayti.storage.CatalogAssetDraft
import app.nayti.storage.CatalogCounts
import app.nayti.storage.CatalogDao
import app.nayti.storage.CatalogInventoryMode
import app.nayti.storage.CatalogInventoryRunEntity
import app.nayti.storage.CatalogInventoryStatus
import app.nayti.storage.CatalogVolumeEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CatalogReconcileResult(
    val accessRevision: AccessRevision,
    val counts: CatalogCounts,
    val scannedVolumes: Int,
    val observedAssets: Int,
    val skippedVolumes: Int,
)

fun interface CatalogClock {
    fun nowMillis(): Long
}

fun interface InventoryTokenFactory {
    fun create(): String
}

class CatalogReconciler(
    private val accessGate: AccessRevisionGate,
    private val mediaStore: MediaStoreGateway,
    private val catalogDao: CatalogDao,
    private val clock: CatalogClock = CatalogClock(System::currentTimeMillis),
    private val tokenFactory: InventoryTokenFactory = InventoryTokenFactory { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    suspend fun reconcile(forceFull: Boolean = false): CatalogReconcileResult =
        mutex.withLock {
            val startedAt = clock.nowMillis()
            catalogDao.abandonRunningInventoryRuns(startedAt)
            val accessPin = accessGate.refresh()
            catalogDao.recordAccessObservation(
                accessScope = accessPin.permission.scope.name,
                processAccessRevision = accessPin.value,
                nowMillis = startedAt,
            )
            if (accessPin.permission.scope == MediaAccessScope.None) {
                catalogDao.applyNoAccess(startedAt)
                return@withLock CatalogReconcileResult(
                    accessRevision = accessPin,
                    counts = catalogDao.counts(),
                    scannedVolumes = 0,
                    observedAssets = 0,
                    skippedVolumes = 0,
                )
            }

            val mounted = mediaStore.mountedVolumes()
            val mountedNames = mounted.mapTo(mutableSetOf()) { it.volumeName }
            catalogDao.volumes()
                .filter { it.volumeName !in mountedNames && it.isMounted }
                .forEach { catalogDao.setVolumeOffline(it.volumeName, clock.nowMillis()) }

            var scannedVolumes = 0
            var observedAssets = 0
            var skippedVolumes = 0
            mounted.forEach { volume ->
                val previous = catalogDao.volume(volume.volumeName)
                val plan =
                    InventoryDecisionPolicy.decide(
                        previous = previous,
                        current = volume,
                        accessScope = accessPin.permission.scope,
                        forceFull = forceFull,
                        nowMillis = clock.nowMillis(),
                    )
                if (plan == null) {
                    skippedVolumes += 1
                } else {
                    val count = reconcileVolume(accessPin, volume, plan)
                    scannedVolumes += 1
                    observedAssets += count
                }
            }
            if (accessGate.refresh() != accessPin) {
                throw InventoryEvidenceChangedException("access-scope")
            }
            CatalogReconcileResult(
                accessRevision = accessPin,
                counts = catalogDao.counts(),
                scannedVolumes = scannedVolumes,
                observedAssets = observedAssets,
                skippedVolumes = skippedVolumes,
            )
        }

    suspend fun markDirty() {
        catalogDao.markAllVolumesDirty()
    }

    private suspend fun reconcileVolume(
        accessPin: AccessRevision,
        volume: MediaVolumeSnapshot,
        plan: InventoryPlan,
    ): Int {
        val startedAt = clock.nowMillis()
        val runId =
            catalogDao.insertInventoryRun(
                CatalogInventoryRunEntity(
                    token = tokenFactory.create(),
                    accessScope = accessPin.permission.scope.name,
                    accessRevision = accessPin.value,
                    mode = plan.mode,
                    startedAtMillis = startedAt,
                    finishedAtMillis = null,
                    status = CatalogInventoryStatus.RUNNING,
                    observedAssetCount = 0,
                ),
            )
        try {
            val inventory =
                mediaStore.inventory(
                    volume = volume,
                    modifiedAfterGeneration = plan.modifiedAfterGeneration,
                )
            inventory.observations.chunked(ObservationBatchSize).forEach { batch ->
                catalogDao.applyObservations(
                    runId = runId,
                    observations = batch.map { it.toDraft() },
                    nowMillis = clock.nowMillis(),
                )
            }

            val accessAfter = accessGate.refresh()
            val volumeAfter =
                mediaStore.mountedVolumes().firstOrNull { it.volumeName == volume.volumeName }
            if (accessAfter != accessPin || volumeAfter != volume) {
                throw InventoryEvidenceChangedException(volume.volumeName)
            }
            catalogDao.completeVolumeInventory(
                runId = runId,
                volumeName = volume.volumeName,
                mediaStoreVersion = volume.version,
                generation = volume.generation,
                accessScope = accessPin.permission.scope.name,
                isFullInventory = inventory.isFullInventory,
                observedAssetCount = inventory.observations.size,
                nowMillis = clock.nowMillis(),
            )
            return inventory.observations.size
        } catch (failure: Throwable) {
            catalogDao.stopInventoryRun(
                runId = runId,
                status = CatalogInventoryStatus.FAILED,
                finishedAtMillis = clock.nowMillis(),
            )
            throw failure
        }
    }

    private fun MediaObservation.toDraft(): CatalogAssetDraft =
        CatalogAssetDraft(
            volumeName = key.volumeName,
            mediaStoreId = key.mediaStoreId,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            orientationDegrees = orientationDegrees,
            generationAdded = generationAdded,
            generationModified = generationModified,
            dateTakenMillis = dateTakenMillis,
            dateModifiedSeconds = dateModifiedSeconds,
            displayName = displayName,
            bucketId = bucketId,
            bucketDisplayName = bucketDisplayName,
            relativePath = relativePath,
            sourceFingerprint = fingerprint,
            isPending = isPending,
            isTrashed = isTrashed,
        )

    private companion object {
        const val ObservationBatchSize = 256
    }
}

data class InventoryPlan(
    val mode: String,
    val modifiedAfterGeneration: Long?,
)

object InventoryDecisionPolicy {
    const val FullInventoryIntervalMillis = 6 * 60 * 60 * 1_000L

    fun decide(
        previous: CatalogVolumeEntity?,
        current: MediaVolumeSnapshot,
        accessScope: MediaAccessScope,
        forceFull: Boolean,
        nowMillis: Long,
    ): InventoryPlan? {
        val lastFullInventoryAtMillis = previous?.lastFullInventoryAtMillis
        val fullRequired =
            forceFull ||
                previous == null ||
                !previous.isMounted ||
                previous.dirty ||
                previous.mediaStoreVersion != current.version ||
                current.generation < previous.generationWatermark ||
                accessScope != MediaAccessScope.Full ||
                lastFullInventoryAtMillis == null ||
                nowMillis - lastFullInventoryAtMillis >= FullInventoryIntervalMillis
        if (fullRequired) return InventoryPlan(CatalogInventoryMode.FULL, null)
        if (current.generation == previous.generationWatermark) return null
        return InventoryPlan(
            mode = CatalogInventoryMode.INCREMENTAL,
            modifiedAfterGeneration = previous.generationWatermark,
        )
    }
}

class InventoryEvidenceChangedException(volumeName: String) :
    IllegalStateException("MediaStore evidence changed during inventory of $volumeName")
