package app.nayti.ui.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.R
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.PhotoAvailability
import app.nayti.indexer.PhotoChannel
import app.nayti.indexer.PhotoEvidence
import app.nayti.indexer.PhotoRegion
import app.nayti.indexer.UnifiedSearchHit
import app.nayti.indexer.UnifiedSearchReason
import app.nayti.platform.media.DecodedMediaImage
import app.nayti.platform.media.MediaKey
import app.nayti.ui.DuplicateUiState
import app.nayti.ui.SimilarUiState
import app.nayti.ui.ViewerUiState
import app.nayti.ui.ViewerUnavailableReason
import app.nayti.ui.assertTouchHeightIsAtLeast
import app.nayti.ui.designsystem.theme.NaytiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoViewerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unavailablePhotoExplainsTheProblemAndOffersRetry() {
        setContent(
            assetId = 7,
            state = ViewerUiState.Unavailable(7, ViewerUnavailableReason.Missing),
        )

        composeRule
            .onNodeWithText(context.getString(R.string.viewer_missing))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.viewer_retry))
            .assertExists()
    }

    @Test
    fun staleStateFromThePreviousPhotoIsNeverRendered() {
        setContent(
            assetId = 8,
            state = ViewerUiState.Unavailable(7, ViewerUnavailableReason.Missing),
        )

        composeRule
            .onNodeWithText(context.getString(R.string.viewer_missing))
            .assertDoesNotExist()
    }

    @Test
    fun readyViewerKeepsActionsVisibleAndMovesMatchEvidenceIntoDetails() {
        val hit =
            UnifiedSearchHit(
                assetId = 7,
                rank = 1,
                tier = 0,
                reason = UnifiedSearchReason.LITERAL_TEXT,
                displaySnippet = "Аптека №14, улица Гагарина",
                matchedRegionOrdinals = listOf(0),
                lexicalRank = 1,
                semanticRank = null,
                visualRank = null,
                visualSimilarityMicros = null,
            )
        setContent(
            assetId = 7,
            state = readyState(),
            searchProvenance = hit,
            previousAssetId = 6,
            nextAssetId = 8,
        )

        composeRule.onNodeWithText(context.getString(R.string.viewer_similar_action)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.viewer_copies_action)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.viewer_text_action)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.viewer_previous_photo))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.viewer_next_photo))
            .assertIsDisplayed()

        composeRule.onNodeWithTag("viewer-match-reason").performClick()
        composeRule.onNodeWithText(hit.displaySnippet!!).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.viewer_match_channel_text)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_match_channel_meaning)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_match_channel_visual)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_match_channel_duplicates)).assertExists()
    }

    @Test
    fun viewerRecoveryActionsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NaytiTheme(darkTheme = true) {
                    PhotoViewerScreen(
                        assetId = 7,
                        state = ViewerUiState.Unavailable(7, ViewerUnavailableReason.Missing),
                        searchProvenance = null,
                        previousAssetId = null,
                        nextAssetId = null,
                        accessRevision = 1,
                        similarState = SimilarUiState.Idle,
                        duplicateState = DuplicateUiState.Idle,
                        onLoadThumbnail = { _, _ -> null },
                        onBack = {},
                        onOpen = {},
                        onClose = {},
                        onOpenAsset = {},
                        onFindSimilar = {},
                        onFindDuplicates = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.viewer_back))
            .assertIsDisplayed()
            .assertTouchHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.viewer_retry))
            .assertIsDisplayed()
            .assertTouchHeightIsAtLeast(48.dp)
    }

    private fun setContent(
        assetId: Long,
        state: ViewerUiState,
        searchProvenance: UnifiedSearchHit? = null,
        previousAssetId: Long? = null,
        nextAssetId: Long? = null,
    ) {
        composeRule.setContent {
            NaytiTheme(darkTheme = true) {
                PhotoViewerScreen(
                    assetId = assetId,
                    state = state,
                    searchProvenance = searchProvenance,
                    previousAssetId = previousAssetId,
                    nextAssetId = nextAssetId,
                    accessRevision = 1,
                    similarState = SimilarUiState.Idle,
                    duplicateState = DuplicateUiState.Idle,
                    onLoadThumbnail = { _, _ -> null },
                    onBack = {},
                    onOpen = {},
                    onClose = {},
                    onOpenAsset = {},
                    onFindSimilar = {},
                    onFindDuplicates = {},
                )
            }
        }
    }

    private fun readyState(): ViewerUiState.Ready {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val constructor = DecodedMediaImage::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        val image = constructor.newInstance(bitmap, 32, 32) as DecodedMediaImage
        val item =
            CatalogItem(
                assetId = 7,
                key = MediaKey("external_primary", 7),
                displayName = "7.jpg",
                bucketDisplayName = "Camera",
                mimeType = "image/jpeg",
                width = 32,
                height = 32,
                dateTakenMillis = 1_700_000_000_000,
            )
        return ViewerUiState.Ready(
            evidence =
                PhotoEvidence(
                    item = item,
                    availability = PhotoAvailability.Available,
                    outsidePreparationPeriod = false,
                    readyChannels = setOf(PhotoChannel.Text, PhotoChannel.Meaning),
                    regions =
                        listOf(
                            PhotoRegion(
                                ordinal = 0,
                                x0Micros = 100_000,
                                y0Micros = 100_000,
                                x1Micros = 900_000,
                                y1Micros = 100_000,
                                x2Micros = 900_000,
                                y2Micros = 300_000,
                                x3Micros = 100_000,
                                y3Micros = 300_000,
                            ),
                        ),
                ),
            image = image,
            matchedRegionOrdinals = setOf(0),
        )
    }
}
