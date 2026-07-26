package app.nayti.ui.preparation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
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
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchCapabilityCoverage
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.storage.IndexOperationState
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.ui.designsystem.theme.NaytiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runningPreparationHasOnePauseActionAndChannelSpecificCounts() {
        var paused = false
        setContent(
            indexing = { runningIndexing() },
            onPause = { paused = true },
        )

        composeRule
            .onNodeWithText(context.getString(R.string.preparation_action_pause))
            .performClick()
        composeRule.runOnIdle { assertTrue(paused) }
        composeRule
            .onAllNodesWithText(context.getString(R.string.preparation_channel_counts, 74, 100))
            .assertCountEquals(4)
        composeRule
            .onNodeWithText(context.getString(R.string.ocr_readiness_counts, 74, 100, 0))
            .assertDoesNotExist()
    }

    @Test
    fun runningPeriodChangeIsDelegatedToTheSafePauseFlow() {
        var periodChangeRequested = false
        setContent(
            indexing = { runningIndexing() },
            onChangePeriod = { periodChangeRequested = true },
        )

        composeRule
            .onNodeWithText(context.getString(R.string.preparation_period_change))
            .performClick()
        composeRule.runOnIdle { assertTrue(periodChangeRequested) }
    }

    private fun setContent(
        indexing: () -> OcrIndexingState,
        onPause: () -> Unit = {},
        onChangePeriod: () -> Unit = {},
    ) {
        composeRule.setContent {
            NaytiTheme {
                val current = indexing()
                PreparationOverview(
                    state = PreparationUiMapper.map(catalog(), modelPack(), current),
                    indexing = current,
                    showMore = false,
                    onPrimaryAction = onPause,
                    onToggleMore = {},
                    onChangePeriod = onChangePeriod,
                    onRetryGaps = {},
                    onCancel = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun catalog() =
        CatalogRuntimeState(
            status = CatalogRuntimeStatus.Ready,
            access =
                AccessRevision(
                    1,
                    MediaPermissionSnapshot(MediaAccessScope.Full, true, true),
                ),
            summary = CatalogSummary.Empty.copy(available = 100),
            recentItems = emptyList(),
            lastErrorCode = null,
        )

    private fun modelPack() =
        ModelPackRuntimeState(
            status = ModelPackRuntimeStatus.Ready,
            installed =
                ModelPackEntity(
                    packId = "test-pack",
                    packVersion = "1",
                    keyId = "test-key",
                    manifestSha256 = "0".repeat(64),
                    relativeDirectory = "test-pack/1",
                    payloadBytes = 1,
                    installedAtMillis = 1,
                    status = ModelPackStatus.INSTALLED_CANDIDATE,
                ),
            candidate = null,
            errorCode = null,
        )

    private fun runningIndexing() =
        OcrIndexingState(
            status = OcrIndexingStatus.Running,
            accessible = 100,
            committed = 74,
            permanentGaps = 0,
            outstanding = 26,
            lastSlicePublished = 1,
            errorCode = null,
            operationId = "operation",
            operationState = IndexOperationState.RUNNING,
            hostType = "USER_FGS",
            capabilities =
                SearchCapability.entries.map { capability ->
                    SearchCapabilityCoverage(
                        capability = capability,
                        accessible = 100,
                        committed = 74,
                        permanentGaps = 0,
                        outstanding = 26,
                    )
                },
            scope =
                IndexingScopeSummary(
                    mode = IndexingScopeMode.SINCE_DATE,
                    takenFromMillis = 1_700_000_000_000,
                    revision = 1,
                    totalAvailable = 1_000,
                    eligibleAssets = 100,
                    unknownDateAssets = 0,
                ),
        )
}
