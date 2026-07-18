package app.nayti.ui

import androidx.compose.ui.test.assertIsDisplayed
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
import app.nayti.ui.theme.NaytiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cleanInstallExplainsPrivacyAndRequestsModelPackFirst() {
        var importRequested = false
        composeRule.setContent {
            NaytiTheme {
                SetupScreen(
                    catalog = catalogWithoutAccess(),
                    modelPack = missingModelPack(),
                    indexing = idleIndexing(),
                    onImportModelPack = { importRequested = true },
                    onRequestAccess = {},
                    onStartIndexing = {},
                    onComplete = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.setup_privacy_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_action_pack)).performClick()
        composeRule.runOnIdle { assertTrue(importRequested) }
    }

    @Test
    fun setupCanBeSkippedWithoutStartingHiddenWork() {
        var completed = false
        composeRule.setContent {
            NaytiTheme {
                SetupScreen(
                    catalog = catalogWithoutAccess(),
                    modelPack = missingModelPack(),
                    indexing = idleIndexing(),
                    onImportModelPack = {},
                    onRequestAccess = {},
                    onStartIndexing = {},
                    onComplete = { completed = true },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.setup_skip)).performClick()
        composeRule.runOnIdle { assertTrue(completed) }
    }

    private fun catalogWithoutAccess() = CatalogRuntimeState(
        status = CatalogRuntimeStatus.PermissionRequired,
        access = AccessRevision(
            value = 1L,
            permission = MediaPermissionSnapshot(
                scope = MediaAccessScope.None,
                readImagesGranted = false,
                selectedImagesGranted = false,
            ),
        ),
        summary = CatalogSummary.Empty,
        recentItems = emptyList(),
        lastErrorCode = null,
    )

    private fun missingModelPack() = ModelPackRuntimeState(
        status = ModelPackRuntimeStatus.Missing,
        installed = null,
        candidate = null,
        errorCode = null,
    )

    private fun idleIndexing() = OcrIndexingState(
        status = OcrIndexingStatus.Idle,
        accessible = 0L,
        committed = 0L,
        permanentGaps = 0L,
        outstanding = 0L,
        lastSlicePublished = 0,
        errorCode = null,
    )
}
