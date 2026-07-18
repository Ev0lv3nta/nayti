package app.nayti.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogSummary
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.storage.IndexOperationState
import app.nayti.ui.theme.NaytiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadinessScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun permanentGapsHaveAnExplicitRetryAction() {
        var retryRequested = false
        composeRule.setContent {
            NaytiTheme {
                OcrReadinessCard(
                    catalog = catalog(),
                    modelPack = modelPack(),
                    indexing = indexingWithGap(),
                    onStartIndexing = {},
                    onPauseIndexing = {},
                    onStopIndexing = {},
                    onCancelIndexing = {},
                    onRetryIndexingGaps = { retryRequested = true },
                )
            }
        }

        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.ocr_retry_gaps)
        composeRule.onNodeWithText(label).performClick()
        composeRule.runOnIdle { assertTrue(retryRequested) }
    }

    private fun catalog() = CatalogRuntimeState(
        status = CatalogRuntimeStatus.Ready,
        access = AccessRevision(
            value = 1,
            permission = MediaPermissionSnapshot(MediaAccessScope.Full, true, false),
        ),
        summary = CatalogSummary.Empty,
        recentItems = emptyList(),
        lastErrorCode = null,
    )

    private fun modelPack() = ModelPackRuntimeState(
        status = ModelPackRuntimeStatus.Missing,
        installed = null,
        candidate = null,
        errorCode = null,
    )

    private fun indexingWithGap() = OcrIndexingState(
        status = OcrIndexingStatus.Ready,
        accessible = 1,
        committed = 0,
        permanentGaps = 1,
        outstanding = 0,
        lastSlicePublished = 0,
        errorCode = null,
        operationId = "operation-with-gaps",
        operationState = IndexOperationState.COMPLETED_WITH_GAPS,
    )
}
