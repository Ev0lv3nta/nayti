package app.nayti.indexer

import android.content.Context
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.AccessRevisionGate
import app.nayti.platform.media.AndroidMediaPermissionReader
import app.nayti.platform.media.AndroidMediaStoreGateway
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.DecodedMediaImage
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaDecodeProbe
import app.nayti.platform.media.MediaKey
import app.nayti.platform.media.MediaStoreChangeObserver
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogCounts
import app.nayti.storage.CatalogStorage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CatalogRuntimeStatus {
    Idle,
    Reconciling,
    Ready,
    PermissionRequired,
    Failed,
}

data class CatalogSummary(
    val total: Long,
    val available: Long,
    val outOfScope: Long,
    val offline: Long,
    val pending: Long,
    val trashed: Long,
    val missing: Long,
) {
    companion object {
        val Empty = CatalogSummary(0, 0, 0, 0, 0, 0, 0)
    }
}

data class CatalogItem(
    val assetId: Long,
    val key: MediaKey,
    val displayName: String?,
    val bucketDisplayName: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val dateTakenMillis: Long?,
)

data class CatalogRuntimeState(
    val status: CatalogRuntimeStatus,
    val access: AccessRevision,
    val summary: CatalogSummary,
    val recentItems: List<CatalogItem>,
    val lastErrorCode: String?,
)

class CatalogRuntime private constructor(
    private val storage: CatalogStorage,
    private val accessGate: AccessRevisionGate,
    private val reconciler: CatalogReconciler,
    private val decoder: BoundedMediaDecoder,
    private val applicationScope: CoroutineScope,
    private val observerFactory: (() -> Unit) -> MediaStoreChangeObserver,
    private val closeStorageOnClose: Boolean,
) : AutoCloseable {
    private val reconcileSignal = Channel<Unit>(Channel.CONFLATED)
    private val fullInventoryPending = AtomicBoolean(true)
    private val dirtyPending = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutableState =
        MutableStateFlow(
            CatalogRuntimeState(
                status = CatalogRuntimeStatus.Idle,
                access = accessGate.pin(),
                summary = CatalogSummary.Empty,
                recentItems = emptyList(),
                lastErrorCode = null,
            ),
        )
    private var changeObserver: MediaStoreChangeObserver? = null

    val state: StateFlow<CatalogRuntimeState> = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        changeObserver =
            observerFactory {
                dirtyPending.set(true)
                fullInventoryPending.set(true)
                reconcileSignal.trySend(Unit)
            }
        applicationScope.launch {
            for (ignored in reconcileSignal) {
                delay(DirtyDebounceMillis)
                val forceFull = fullInventoryPending.getAndSet(false)
                if (dirtyPending.getAndSet(false)) reconciler.markDirty()
                reconcileNow(forceFull)
            }
        }
        refreshAccess(forceFull = true)
    }

    fun refreshAccess(forceFull: Boolean = false) {
        val before = accessGate.pin()
        val after = accessGate.refresh()
        publishAccessChange(before, after, forceFull)
    }

    fun onPermissionResult() {
        val before = accessGate.pin()
        val after = accessGate.invalidate()
        publishAccessChange(before, after, forceFull = true)
    }

    private fun publishAccessChange(
        before: AccessRevision,
        after: AccessRevision,
        forceFull: Boolean,
    ) {
        if (after != before) {
            mutableState.value =
                mutableState.value.copy(
                    status =
                        if (after.permission.scope == MediaAccessScope.None) {
                            CatalogRuntimeStatus.PermissionRequired
                        } else {
                            CatalogRuntimeStatus.Reconciling
                        },
                    access = after,
                    summary = mutableState.value.summary.copy(available = 0),
                    recentItems = emptyList(),
                    lastErrorCode = null,
                )
            fullInventoryPending.set(true)
        } else if (forceFull) {
            fullInventoryPending.set(true)
        }
        reconcileSignal.trySend(Unit)
    }

    suspend fun probe(assetId: Long, accessPin: AccessRevision): MediaDecodeProbe =
        withContext(Dispatchers.IO) {
            check(accessGate.isCurrent(accessPin)) { "Access revision is stale" }
            val asset = checkNotNull(storage.catalogDao.asset(assetId))
            check(asset.availability == CatalogAvailability.AVAILABLE) {
                "Asset is not currently available"
            }
            val result = decoder.probe(MediaKey(asset.volumeName, asset.mediaStoreId))
            val after = accessGate.refresh()
            check(after == accessPin) { "Access changed while decoding" }
            result
        }

    suspend fun decode(assetId: Long, accessPin: AccessRevision): DecodedMediaImage =
        withContext(Dispatchers.IO) {
            check(accessGate.isCurrent(accessPin)) { "Access revision is stale" }
            val asset = checkNotNull(storage.catalogDao.asset(assetId))
            check(asset.availability == CatalogAvailability.AVAILABLE) {
                "Asset is not currently available"
            }
            val decoded = decoder.decode(MediaKey(asset.volumeName, asset.mediaStoreId))
            val after = accessGate.refresh()
            if (after != accessPin) {
                decoded.close()
                error("Access changed while decoding")
            }
            decoded
        }

    override fun close() {
        changeObserver?.close()
        reconcileSignal.close()
        applicationScope.cancel()
        if (closeStorageOnClose) storage.close()
    }

    private suspend fun reconcileNow(forceFull: Boolean) {
        val access = accessGate.pin()
        mutableState.value =
            mutableState.value.copy(
                status = CatalogRuntimeStatus.Reconciling,
                access = access,
                lastErrorCode = null,
            )
        try {
            val result = reconciler.reconcile(forceFull)
            val currentAccess = result.accessRevision
            val items =
                if (currentAccess.permission.scope == MediaAccessScope.None) {
                    emptyList()
                } else {
                    storage.catalogDao.availableAssets().take(RecentItemLimit).map { it.toItem() }
                }
            mutableState.value =
                CatalogRuntimeState(
                    status =
                        if (currentAccess.permission.scope == MediaAccessScope.None) {
                            CatalogRuntimeStatus.PermissionRequired
                        } else {
                            CatalogRuntimeStatus.Ready
                        },
                    access = currentAccess,
                    summary = result.counts.toSummary(currentAccess.permission.scope),
                    recentItems = items,
                    lastErrorCode = null,
                )
        } catch (failure: SecurityException) {
            publishFailure("MEDIA_ACCESS_CHANGED")
        } catch (failure: InventoryEvidenceChangedException) {
            publishFailure("INVENTORY_CHANGED")
        } catch (failure: RuntimeException) {
            publishFailure(failure::class.java.simpleName.uppercase())
        }
    }

    private fun publishFailure(code: String) {
        val access = accessGate.refresh()
        mutableState.value =
            mutableState.value.copy(
                status =
                    if (access.permission.scope == MediaAccessScope.None) {
                        CatalogRuntimeStatus.PermissionRequired
                    } else {
                        CatalogRuntimeStatus.Failed
                    },
                access = access,
                recentItems = emptyList(),
                lastErrorCode = code,
            )
    }

    private fun CatalogCounts.toSummary(scope: MediaAccessScope): CatalogSummary =
        CatalogSummary(
            total = total,
            available = if (scope == MediaAccessScope.None) 0 else available,
            outOfScope = outOfScope,
            offline = offline,
            pending = pending,
            trashed = trashed,
            missing = missing,
        )

    private fun CatalogAssetEntity.toItem(): CatalogItem =
        CatalogItem(
            assetId = assetId,
            key = MediaKey(volumeName, mediaStoreId),
            displayName = displayName,
            bucketDisplayName = bucketDisplayName,
            mimeType = mimeType,
            width = width,
            height = height,
            dateTakenMillis = dateTakenMillis,
        )

    companion object {
        fun create(context: Context): CatalogRuntime =
            create(context, CatalogStorage.open(context), closeStorageOnClose = true)

        fun create(context: Context, storage: CatalogStorage): CatalogRuntime =
            create(context, storage, closeStorageOnClose = false)

        private fun create(
            context: Context,
            storage: CatalogStorage,
            closeStorageOnClose: Boolean,
        ): CatalogRuntime {
            val applicationContext = context.applicationContext
            val permissionReader = AndroidMediaPermissionReader(applicationContext)
            val accessGate = AccessRevisionGate(permissionReader.read(), permissionReader)
            val mediaStore = AndroidMediaStoreGateway(applicationContext)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return CatalogRuntime(
                storage = storage,
                accessGate = accessGate,
                reconciler = CatalogReconciler(accessGate, mediaStore, storage.catalogDao),
                decoder = BoundedMediaDecoder(applicationContext.contentResolver, mediaStore),
                applicationScope = scope,
                observerFactory = { onDirty ->
                    MediaStoreChangeObserver(applicationContext, onDirty)
                },
                closeStorageOnClose = closeStorageOnClose,
            )
        }

        private const val DirtyDebounceMillis = 500L
        private const val RecentItemLimit = 100
    }
}
