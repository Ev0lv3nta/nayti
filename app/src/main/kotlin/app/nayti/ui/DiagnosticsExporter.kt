package app.nayti.ui

import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import app.nayti.indexer.SearchCapabilityCoverage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStreamWriter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticsSnapshot(
    val appVersion: String,
    val sdkInt: Int,
    val device: String,
    val catalogStatus: String,
    val accessScope: String,
    val catalogTotal: Long,
    val catalogAvailable: Long,
    val modelPackStatus: String,
    val activeModelPackVersion: String?,
    val candidateModelPackVersion: String?,
    val preparationStatus: String,
    val preparationErrorCode: String?,
    val capabilities: List<SearchCapabilityCoverage>,
    val storage: LocalStorageSummary,
    val indexingScopeMode: String? = null,
    val indexingScopeTakenFromMillis: Long? = null,
    val indexingScopeRevision: Long? = null,
    val indexingScopeEligibleAssets: Long? = null,
    val preparationActiveDurationMillis: Long? = null,
    val estimatedAllMediaDurationMillis: Long? = null,
)

sealed interface DiagnosticsExportState {
    data object Idle : DiagnosticsExportState
    data object Writing : DiagnosticsExportState
    data object Saved : DiagnosticsExportState
    data object Failed : DiagnosticsExportState
}

@Singleton
class DiagnosticsExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun export(destination: Uri, snapshot: DiagnosticsSnapshot) = withContext(Dispatchers.IO) {
        val output = checkNotNull(context.contentResolver.openOutputStream(destination, "wt"))
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("format").value(Format)
            json.name("generatedAtUtc").value(Instant.now().toString())
            json.name("privacy").value(PrivacyStatement)
            json.name("app").beginObject()
            json.name("version").value(snapshot.appVersion)
            json.name("sdkInt").value(snapshot.sdkInt.toLong())
            json.name("device").value(snapshot.device)
            json.endObject()
            json.name("catalog").beginObject()
            json.name("status").value(snapshot.catalogStatus)
            json.name("accessScope").value(snapshot.accessScope)
            json.name("total").value(snapshot.catalogTotal)
            json.name("available").value(snapshot.catalogAvailable)
            json.endObject()
            json.name("modelPack").beginObject()
            json.name("status").value(snapshot.modelPackStatus)
            json.nullableName("activeVersion", snapshot.activeModelPackVersion)
            json.nullableName("candidateVersion", snapshot.candidateModelPackVersion)
            json.endObject()
            json.name("preparation").beginObject()
            json.name("status").value(snapshot.preparationStatus)
            json.nullableName("errorCode", snapshot.preparationErrorCode)
            json.nullableName("scopeMode", snapshot.indexingScopeMode)
            json.nullableLongName("scopeTakenFromMillis", snapshot.indexingScopeTakenFromMillis)
            json.nullableLongName("scopeRevision", snapshot.indexingScopeRevision)
            json.nullableLongName("scopeEligibleAssets", snapshot.indexingScopeEligibleAssets)
            json.nullableLongName("activeDurationMillis", snapshot.preparationActiveDurationMillis)
            json.nullableLongName("estimatedAllMediaDurationMillis", snapshot.estimatedAllMediaDurationMillis)
            json.name("capabilities").beginArray()
            snapshot.capabilities.forEach { coverage ->
                json.beginObject()
                json.name("capability").value(coverage.capability.name)
                json.name("accessible").value(coverage.accessible)
                json.name("committed").value(coverage.committed)
                json.name("permanentGaps").value(coverage.permanentGaps)
                json.name("outstanding").value(coverage.outstanding)
                json.endObject()
            }
            json.endArray()
            json.endObject()
            json.name("storage").beginObject()
            json.name("indexBytes").value(snapshot.storage.indexBytes)
            json.name("modelBytes").value(snapshot.storage.modelBytes)
            json.endObject()
            json.endObject()
        }
    }

    private fun JsonWriter.nullableName(name: String, value: String?) {
        name(name)
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableLongName(name: String, value: Long?) {
        name(name)
        if (value == null) nullValue() else value(value)
    }

    private companion object {
        const val Format = "nayti-diagnostics-v1"
        const val PrivacyStatement = "No filenames, media paths, recognized text, thumbnails, or queries are included."
    }
}
