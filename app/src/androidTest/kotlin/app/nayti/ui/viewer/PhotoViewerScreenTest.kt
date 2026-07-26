package app.nayti.ui.viewer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.R
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

    private fun setContent(assetId: Long, state: ViewerUiState) {
        composeRule.setContent {
            NaytiTheme(darkTheme = true) {
                PhotoViewerScreen(
                    assetId = assetId,
                    state = state,
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
}
