package app.nayti.ui

import app.nayti.indexer.CatalogItem
import app.nayti.platform.media.MediaKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSequenceTest {
    @Test
    fun libraryOrderIsUsedWhenThePhotoCameFromTheLibrary() {
        val library =
            LibraryUiState(
                items = listOf(photo(3), photo(2), photo(1)),
                totalCount = 3,
            )

        assertEquals(
            listOf(3L, 2L, 1L),
            viewerSequence(
                assetId = 2,
                library = library,
                search = SearchUiState.Idle,
                similar = SimilarUiState.Idle,
                duplicates = DuplicateUiState.Idle,
            ),
        )
    }

    @Test
    fun anAssetOutsideTheCurrentCollectionsStillOpensAsASinglePhoto() {
        assertEquals(
            listOf(42L),
            viewerSequence(
                assetId = 42,
                library = LibraryUiState(),
                search = SearchUiState.Idle,
                similar = SimilarUiState.Idle,
                duplicates = DuplicateUiState.Idle,
            ),
        )
    }

    private fun photo(assetId: Long) =
        CatalogItem(
            assetId = assetId,
            key = MediaKey("external_primary", assetId),
            displayName = "$assetId.jpg",
            bucketDisplayName = "Camera",
            mimeType = "image/jpeg",
            width = 1_080,
            height = 1_080,
            dateTakenMillis = assetId,
        )
}
