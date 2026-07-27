package app.nayti.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import app.nayti.ui.assertTouchHeightIsAtLeast
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

    @Test
    fun selectedSearchChannelsSurviveStateRestorationAndKeepOneEnabled() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { TestContent() }

        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_text))
            .performClick()
            .assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_meaning))
            .performClick()
            .assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_photo))
            .assertIsOn()
            .performClick()
            .assertIsOn()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_text))
            .assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_meaning))
            .assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_photo))
            .assertIsOn()
    }

    @Test
    fun libraryActionsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                TestContent()
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.search_surface_where))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_text))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_meaning))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_photo))
            .assertIsDisplayed()
    }

    @Test
    fun mediumLayoutUsesFivePhotoColumnsAndAccessibleSearchActions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                TestContent(items = (1L..6L).map(::photo))
            }
        }

        val firstRow = (1L..5L).map { id ->
            composeRule.onNodeWithTag("library-photo-$id").fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(1, firstRow.distinct().size)
        composeRule.onNode(
            hasText(context.getString(R.string.search_surface_where)) and hasClickAction(),
        )
            .assertTouchHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_text))
            .assertTouchHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_meaning))
            .assertTouchHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.search_redesign_mode_photo))
            .assertTouchHeightIsAtLeast(48.dp)
    }

    @Composable
    private fun TestContent(
        search: SearchUiState = SearchUiState.Idle,
        items: List<CatalogItem> = listOf(photo()),
        onSearch: (String, SearchFilter, SearchChannelSelection) -> Unit = { _, _, _ -> },
        onCancelSearch: () -> Unit = {},
    ) {
        NaytiTheme {
            LibrarySearchScreen(
                library = LibraryUiState(
                    items = items,
                    totalCount = items.size.toLong(),
                ),
                search = search,
                indexing = indexing(),
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
            )
        }
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
                    indexing = indexing(),
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
                )
            }
        }
    }

    private fun indexing() = OcrIndexingState(
        status = OcrIndexingStatus.Ready,
        accessible = 1,
        committed = 1,
        permanentGaps = 0,
        outstanding = 0,
        lastSlicePublished = 1,
        errorCode = null,
    )

    private fun photo(assetId: Long = 1) = CatalogItem(
        assetId = assetId,
        key = MediaKey("external_primary", assetId),
        displayName = "photo-$assetId.jpg",
        bucketDisplayName = "Camera $assetId",
        mimeType = "image/jpeg",
        width = 1_080,
        height = 1_080,
        dateTakenMillis = 1_700_000_000_000,
    )
}
