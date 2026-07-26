package app.nayti.indexer

import app.nayti.platform.media.MediaKey
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel

data class LibraryAlbumFacet(
    val bucketId: Long,
    val displayName: String,
    val assetCount: Long,
)

data class LibraryMimeFacet(
    val mimeType: String,
    val assetCount: Long,
)

data class LibraryFilterFacets(
    val albums: List<LibraryAlbumFacet>,
    val mimeTypes: List<LibraryMimeFacet>,
) {
    companion object {
        val Empty = LibraryFilterFacets(emptyList(), emptyList())
    }
}

data class LibraryPage(
    val offset: Int,
    val totalCount: Long,
    val items: List<CatalogItem>,
    val facets: LibraryFilterFacets,
)

enum class PhotoAvailability {
    Available,
    AccessRemoved,
    VolumeOffline,
    Pending,
    Trashed,
    Missing,
}

enum class PhotoChannel {
    Text,
    Meaning,
    Visual,
    Duplicates,
}

data class PhotoRegion(
    val ordinal: Int,
    val x0Micros: Int,
    val y0Micros: Int,
    val x1Micros: Int,
    val y1Micros: Int,
    val x2Micros: Int,
    val y2Micros: Int,
    val x3Micros: Int,
    val y3Micros: Int,
)

data class PhotoEvidence(
    val item: CatalogItem,
    val availability: PhotoAvailability,
    val outsidePreparationPeriod: Boolean,
    val readyChannels: Set<PhotoChannel>,
    val regions: List<PhotoRegion>,
)

/**
 * Read-only boundary between the photo library UI and storage.
 *
 * Pages are ordered newest-first and already constrained to the selected preparation period. UI
 * code receives stable catalog read models and never needs Room entities or SQL.
 */
class LibraryFeed(private val storage: CatalogStorage) {
    suspend fun loadPage(offset: Int, limit: Int): LibraryPage {
        require(offset >= 0)
        require(limit in 1..MaximumPageSize)
        val scope = storage.catalogDao.indexingScopeSummary()
        val assets = storage.catalogDao.indexableAssetPage(offset, limit)
        val facets = storage.catalogDao.searchFilterFacets()
        return LibraryPage(
            offset = offset,
            totalCount = scope.eligibleAssets,
            items = assets.map { asset -> asset.toCatalogItem() },
            facets = LibraryFilterFacets(
                albums = facets.albums.map { LibraryAlbumFacet(it.bucketId, it.displayName, it.assetCount) },
                mimeTypes = facets.mimeTypes.map { LibraryMimeFacet(it.mimeType, it.assetCount) },
            ),
        )
    }

    suspend fun item(assetId: Long): CatalogItem? =
        storage.catalogDao.asset(assetId)?.toCatalogItem()

    suspend fun photoEvidence(assetId: Long): PhotoEvidence? {
        require(assetId > 0)
        val asset = storage.catalogDao.asset(assetId) ?: return null
        val snapshotId = storage.vectorIndexDao.activeSnapshotId()
        val snapshotChannels =
            if (snapshotId == null) {
                emptyMap()
            } else {
                storage.vectorIndexDao
                    .snapshotChannels(snapshotId)
                    .associateBy { channel -> channel.channel }
            }
        val readyChannels =
            ChannelMapping.mapNotNullTo(mutableSetOf()) { (channel, photoChannel) ->
                val snapshotChannel = snapshotChannels[channel] ?: return@mapNotNullTo null
                val publication = storage.indexStateDao.publication(assetId, channel)
                    ?: return@mapNotNullTo null
                photoChannel.takeIf {
                    publication.sourceFingerprint == asset.sourceFingerprint &&
                        publication.pipelineVersion == snapshotChannel.pipelineVersion &&
                        publication.componentHash == snapshotChannel.componentHash
                }
            }
        val ocrChannel = snapshotChannels[IndexChannel.OCR]
        val regions =
            if (
                asset.availability == CatalogAvailability.AVAILABLE &&
                ocrChannel != null &&
                PhotoChannel.Text in readyChannels
            ) {
                storage.ocrDao
                    .eligibleAsset(
                        assetId,
                        ocrChannel.pipelineVersion,
                        ocrChannel.componentHash,
                    )
                    ?.regions
                    .orEmpty()
                    .map { region ->
                        PhotoRegion(
                            ordinal = region.ordinal,
                            x0Micros = region.x0Micros,
                            y0Micros = region.y0Micros,
                            x1Micros = region.x1Micros,
                            y1Micros = region.y1Micros,
                            x2Micros = region.x2Micros,
                            y2Micros = region.y2Micros,
                            x3Micros = region.x3Micros,
                            y3Micros = region.y3Micros,
                        )
                    }
            } else {
                emptyList()
            }
        return PhotoEvidence(
            item = asset.toCatalogItem(),
            availability = asset.availability.toPhotoAvailability(),
            outsidePreparationPeriod = storage.catalogDao.isAssetOutsideIndexingScope(assetId),
            readyChannels = readyChannels,
            regions = regions,
        )
    }

    private fun CatalogAssetEntity.toCatalogItem() = CatalogItem(
        assetId = assetId,
        key = MediaKey(volumeName, mediaStoreId),
        displayName = displayName,
        bucketDisplayName = bucketDisplayName,
        mimeType = mimeType,
        width = width,
        height = height,
        dateTakenMillis = dateTakenMillis ?: dateModifiedSeconds?.times(1_000),
    )

    private fun String.toPhotoAvailability(): PhotoAvailability =
        when (this) {
            CatalogAvailability.AVAILABLE -> PhotoAvailability.Available
            CatalogAvailability.OUT_OF_SCOPE -> PhotoAvailability.AccessRemoved
            CatalogAvailability.VOLUME_OFFLINE -> PhotoAvailability.VolumeOffline
            CatalogAvailability.PENDING -> PhotoAvailability.Pending
            CatalogAvailability.TRASHED -> PhotoAvailability.Trashed
            CatalogAvailability.MISSING_UNCONFIRMED,
            CatalogAvailability.DELETED,
            -> PhotoAvailability.Missing
            else -> PhotoAvailability.Missing
        }

    private companion object {
        const val MaximumPageSize = 200
        val ChannelMapping =
            listOf(
                IndexChannel.OCR to PhotoChannel.Text,
                IndexChannel.OCR_SEMANTIC to PhotoChannel.Meaning,
                IndexChannel.VISUAL to PhotoChannel.Visual,
                IndexChannel.PHASH to PhotoChannel.Duplicates,
            )
    }
}
