package app.nayti.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun destructiveIndexResetRequiresExplicitConfirmation() {
        var resetRequested = false
        setContent(onResetSearchData = { resetRequested = true })

        val action = context.getString(R.string.reset_index_action)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(action))
        composeRule.onNodeWithText(action).performClick()
        composeRule.onNodeWithText(context.getString(R.string.reset_index_confirm_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.reset_index_confirm_action))
            .performClick()

        composeRule.runOnIdle { assertTrue(resetRequested) }
    }

    @Test
    fun rollbackShowsTheExactTargetVersion() {
        var rollbackRequested = false
        setContent(
            rollback = ModelPackRollbackState.Available("2.0", "1.0"),
            onRollback = { rollbackRequested = true },
        )

        composeRule.onNodeWithText(context.getString(R.string.settings_advanced_show))
            .performScrollTo()
            .performClick()
        val action = context.getString(R.string.model_pack_rollback_action, "1.0")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(action))
        composeRule.onNodeWithText(action).performClick()

        composeRule.runOnIdle { assertTrue(rollbackRequested) }
    }

    @Test
    fun retainedDataUsesTheSameExplicitFullResetConfirmation() {
        var resetRequested = false
        setContent(
            retainedQuarantine = 4,
            onResetSearchData = { resetRequested = true },
        )

        val action = context.getString(R.string.reset_index_action)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(action))
        composeRule.onNodeWithText(action).performClick()
        composeRule.runOnIdle { assertTrue(!resetRequested) }
        composeRule.onNodeWithText(context.getString(R.string.reset_index_confirm_details))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.reset_index_confirm_action))
            .performClick()

        composeRule.runOnIdle { assertTrue(resetRequested) }
    }

    @Test
    fun settingsUseProductLevelGroups() {
        setContent()

        composeRule.onNodeWithText(context.getString(R.string.settings_appearance_section))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_media_section))
            .assertIsDisplayed()
        val data = context.getString(R.string.settings_storage_section)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(data))
        composeRule.onNodeWithText(data).assertIsDisplayed()
    }

    @Test
    fun destructiveActionRemainsReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NaytiTheme {
                    SettingsScreen(
                        catalog = catalog(0),
                        modelPack = modelPack(),
                        localStorage = LocalStorageSummary(1_024, 2_048),
                        diagnosticsExport = DiagnosticsExportState.Idle,
                        searchDataReset = SearchDataResetState.Idle,
                        modelPackRollback = ModelPackRollbackState.Unavailable("1.0"),
                        indexing = indexing(),
                        onRequestAccess = {},
                        onImportModelPack = {},
                        onRefreshStorage = {},
                        onExportDiagnostics = {},
                        onResetSearchData = {},
                        onRollbackModelPack = {},
                    )
                }
            }
        }

        val action = context.getString(R.string.reset_index_action)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(action))
        composeRule.onNode(hasText(action) and hasClickAction())
            .assertIsDisplayed()
            .assertTouchHeightIsAtLeast(48.dp)
    }

    private fun setContent(
        rollback: ModelPackRollbackState = ModelPackRollbackState.Unavailable("1.0"),
        retainedQuarantine: Long = 0,
        onResetSearchData: () -> Unit = {},
        onRollback: () -> Unit = {},
    ) {
        composeRule.setContent {
            NaytiTheme {
                SettingsScreen(
                    catalog = catalog(retainedQuarantine),
                    modelPack = modelPack(),
                    localStorage = LocalStorageSummary(indexBytes = 1_024, modelBytes = 2_048),
                    diagnosticsExport = DiagnosticsExportState.Idle,
                    searchDataReset = SearchDataResetState.Idle,
                    modelPackRollback = rollback,
                    indexing = indexing(),
                    onRequestAccess = {},
                    onImportModelPack = {},
                    onRefreshStorage = {},
                    onExportDiagnostics = {},
                    onResetSearchData = onResetSearchData,
                    onRollbackModelPack = onRollback,
                )
            }
        }
    }

    private fun catalog(retainedQuarantine: Long) = CatalogRuntimeState(
        status = CatalogRuntimeStatus.Ready,
        access = AccessRevision(
            value = 1,
            permission = MediaPermissionSnapshot(MediaAccessScope.Full, true, false),
        ),
        summary = CatalogSummary.Empty.copy(retainedQuarantine = retainedQuarantine),
        recentItems = emptyList(),
        lastErrorCode = null,
    )

    private fun modelPack() = ModelPackRuntimeState(
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

    private fun indexing(
        committed: Long = 120,
        outstanding: Long = 0,
    ) =
        OcrIndexingState(
            status = OcrIndexingStatus.Ready,
            accessible = 120,
            committed = committed,
            permanentGaps = 0,
            outstanding = outstanding,
            lastSlicePublished = 0,
            errorCode = null,
            scope =
                IndexingScopeSummary(
                    mode = IndexingScopeMode.ALL,
                    takenFromMillis = null,
                    revision = 1,
                    totalAvailable = 1_200,
                    eligibleAssets = 1_200,
                    unknownDateAssets = 0,
                ),
        )
}
