package app.nayti.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.R
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaKey
import app.nayti.ui.LibraryUiState
import app.nayti.ui.SearchUiState
import app.nayti.ui.designsystem.theme.NaytiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun typingDoesNotSearchAndImeSubmitsExactlyOnce() {
        val requests = mutableListOf<String>()
        setContent(
            onSearch = { query, _, _ -> requests += query },
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("собака")
        composeRule.runOnIdle { assertTrue(requests.isEmpty()) }
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.runOnIdle { assertEquals(listOf("собака"), requests) }
    }

    @Test
    fun cancelAppearsOnlyAfterLongSearchAndCallsTheRealCancellationAction() {
        var searchState by mutableStateOf<SearchUiState>(
            SearchUiState.Searching("собака", SearchFilter.None, SearchChannelSelection.All),
        )
        var cancelled = false
        composeRule.mainClock.autoAdvance = false
        setContent(
            search = { searchState },
            onCancelSearch = {
                cancelled = true
                searchState = SearchUiState.Cancelled("собака")
            },
        )

        val cancel = context.getString(R.string.search_surface_cancel)
        composeRule.onNodeWithText(cancel).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(801)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(cancel).performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    private fun setContent(
        search: () -> SearchUiState = { SearchUiState.Idle },
        onSearch: (String, SearchFilter, SearchChannelSelection) -> Unit = { _, _, _ -> },
        onCancelSearch: () -> Unit = {},
    ) {
        composeRule.setContent {
            NaytiTheme {
                LibrarySearchScreen(
                    library = LibraryUiState(
                        items = listOf(photo()),
                        totalCount = 1,
                    ),
                    search = search(),
                    indexing = OcrIndexingState(
                        status = OcrIndexingStatus.Ready,
                        accessible = 1,
                        committed = 1,
                        permanentGaps = 0,
                        outstanding = 0,
                        lastSlicePublished = 1,
                        errorCode = null,
                    ),
                    accessScope = MediaAccessScope.Full,
                    accessRevision = 1,
                    modelReady = true,
                    onLoadThumbnail = { _, _ -> null },
                    onLoadMore = {},
                    onRetryLibrary = {},
                    onRequestAccess = {},
                    onSearch = onSearch,
                    onCancelSearch = onCancelSearch,
                    onOpenAsset = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun photo() = CatalogItem(
        assetId = 1,
        key = MediaKey("external_primary", 1),
        displayName = "photo.jpg",
        bucketDisplayName = "Camera",
        mimeType = "image/jpeg",
        width = 1_080,
        height = 1_080,
        dateTakenMillis = 1_700_000_000_000,
    )
}
