package app.nayti.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30, maxSdkVersion = 30)
class ScreenshotRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupLightMatchesApi30Baseline() {
        composeRule.setContent {
            NaytiTheme(darkTheme = false) {
                SetupScreen(
                    catalog = catalog(),
                    modelPack = modelPack(),
                    indexing = indexing(),
                    onImportModelPack = {},
                    onRequestAccess = {},
                    onStartIndexing = {},
                    onComplete = {},
                )
            }
        }

        assertGolden("setup-light-api30.png")
    }

    @Test
    fun dataDarkMatchesApi30Baseline() {
        composeRule.setContent {
            NaytiTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DataScreen(
                        catalog = catalog(),
                        modelPack = modelPack(),
                        localStorage = LocalStorageSummary(indexBytes = 24_576, modelBytes = 314_572_800),
                        diagnosticsExport = DiagnosticsExportState.Idle,
                        searchDataReset = SearchDataResetState.Idle,
                        modelPackRollback = ModelPackRollbackState.Unavailable(null),
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

        assertGolden("data-dark-api30.png")
    }

    private fun assertGolden(name: String) {
        val actual = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = runCatching {
            instrumentation.context.assets.open("goldens/$name").use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (expected == null) {
            val outputDirectory = instrumentation.targetContext.cacheDir
            check(outputDirectory.mkdirs() || outputDirectory.isDirectory)
            val output = File(outputDirectory, name)
            output.outputStream().use { stream ->
                check(actual.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            error("Missing screenshot baseline. Generated candidate: ${output.absolutePath}")
        }
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val expectedRow = IntArray(expected.width)
        val actualRow = IntArray(actual.width)
        var changedPixels = 0L
        for (y in 0 until expected.height) {
            expected.getPixels(expectedRow, 0, expected.width, 0, y, expected.width, 1)
            actual.getPixels(actualRow, 0, actual.width, 0, y, actual.width, 1)
            for (x in expectedRow.indices) {
                if (maximumChannelDelta(expectedRow[x], actualRow[x]) > ChannelTolerance) {
                    changedPixels++
                }
            }
        }
        val totalPixels = actual.width.toLong() * actual.height
        assertTrue(
            "Screenshot changed by ${changedPixels * 100.0 / totalPixels}%",
            changedPixels <= (totalPixels * MaximumChangedFraction).toLong(),
        )
    }

    private fun maximumChannelDelta(left: Int, right: Int): Int =
        maxOf(
            kotlin.math.abs((left ushr 24) - (right ushr 24)),
            kotlin.math.abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)),
            kotlin.math.abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)),
            kotlin.math.abs((left and 0xff) - (right and 0xff)),
        )

    private fun catalog() = CatalogRuntimeState(
        status = CatalogRuntimeStatus.PermissionRequired,
        access = AccessRevision(
            value = 1,
            permission = MediaPermissionSnapshot(MediaAccessScope.None, false, false),
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

    private fun indexing() = OcrIndexingState(
        status = OcrIndexingStatus.Idle,
        accessible = 0,
        committed = 0,
        permanentGaps = 0,
        outstanding = 0,
        lastSlicePublished = 0,
        errorCode = null,
    )

    private companion object {
        const val ChannelTolerance = 4
        const val MaximumChangedFraction = 0.001
    }
}
