package app.nayti.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogSummary
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.ui.theme.NaytiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun destructiveIndexResetRequiresExplicitConfirmation() {
        var resetRequested = false
        setContent(onResetSearchData = { resetRequested = true })

        composeRule.onNodeWithText(context.getString(R.string.reset_index_action))
            .performScrollTo()
            .performClick()
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

        val action = context.getString(R.string.model_pack_rollback_action, "1.0")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(action))
        composeRule.onNodeWithText(action).performClick()

        composeRule.runOnIdle { assertTrue(rollbackRequested) }
    }

    private fun setContent(
        rollback: ModelPackRollbackState = ModelPackRollbackState.Unavailable("1.0"),
        onResetSearchData: () -> Unit = {},
        onRollback: () -> Unit = {},
    ) {
        composeRule.setContent {
            NaytiTheme {
                DataScreen(
                    catalog = catalog(),
                    modelPack = modelPack(),
                    localStorage = LocalStorageSummary(indexBytes = 1_024, modelBytes = 2_048),
                    diagnosticsExport = DiagnosticsExportState.Idle,
                    searchDataReset = SearchDataResetState.Idle,
                    modelPackRollback = rollback,
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
        status = ModelPackRuntimeStatus.Ready,
        installed = null,
        candidate = null,
        errorCode = null,
    )
}
