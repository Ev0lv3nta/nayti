package app.nayti.indexer

import app.nayti.platform.media.MediaKey
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogStorage

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

    private companion object {
        const val MaximumPageSize = 200
    }
}
