package app.nayti.ui

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchCapabilityCoverage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsExporterTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportContainsOperationalStateWithoutUserContent() = runBlocking {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "nayti-diagnostics-test.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                },
            ),
        )
        try {
            DiagnosticsExporter(context).export(
                destination = uri,
                snapshot = DiagnosticsSnapshot(
                    appVersion = "test",
                    sdkInt = Build.VERSION.SDK_INT,
                    device = "test-device",
                    catalogStatus = "Ready",
                    accessScope = "Selected",
                    catalogTotal = 12,
                    catalogAvailable = 8,
                    modelPackStatus = "Ready",
                    activeModelPackVersion = "alpha-test",
                    candidateModelPackVersion = null,
                    preparationStatus = "Paused",
                    preparationErrorCode = "BATTERY_LOW",
                    capabilities = listOf(
                        SearchCapabilityCoverage(
                            capability = SearchCapability.TEXT,
                            accessible = 8,
                            committed = 7,
                            permanentGaps = 0,
                            outstanding = 1,
                        ),
                    ),
                    storage = LocalStorageSummary(indexBytes = 1024, modelBytes = 2048),
                ),
            )
            val report = resolver.openInputStream(uri).use { input ->
                checkNotNull(input).bufferedReader().readText()
            }
            assertTrue(report.contains("\"format\": \"nayti-diagnostics-v1\""))
            assertTrue(report.contains("\"BATTERY_LOW\""))
            assertFalse(report.contains("/storage/emulated"))
            assertFalse(report.contains("secret user query"))
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
