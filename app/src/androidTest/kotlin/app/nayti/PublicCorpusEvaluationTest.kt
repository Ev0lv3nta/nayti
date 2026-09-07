package app.nayti

import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.PerceptualHashSearchStatus
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import app.nayti.indexing.IndexingStartResult
import app.nayti.ml.runtime.pack.FileModelPackSource
import dagger.hilt.EntryPoints
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: real MediaStore, signed pack, production index and queries. No fake encoders. */
@RunWith(AndroidJUnit4::class)
class PublicCorpusEvaluationTest {
    @Test fun evaluatePublicCorpus() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit corpus run only", arguments.getString("naytiEvaluation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.nayti.debug") { "Never evaluate in the personal release installation" }
        check(Build.SUPPORTED_ABIS.first() == "arm64-v8a") { "Actual ARM64 inference required" }
        val commit = requireNotNull(arguments.getString("sourceCommit"))
        check(Regex("[0-9a-f]{40}").matches(commit))
        val root = File(context.filesDir, "evaluation")
        val manifestFile = File(root, "input/manifest.json")
        val manifestHash = manifestFile.inputStream().use(::sha256)
        check(manifestHash == arguments.getString("manifestSha256"))
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getString("corpus") == "nayti-public-v1")
        val assets = manifest.getJSONArray("assets").objects()
        val byName = assets.associateBy { it.getString("id") + ".jpg" }
        check(byName.size == assets.size)
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val existing = mutableMapOf<String, android.net.Uri>()
        resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                check(name in byName && name !in existing) { "Non-corpus media present; use an isolated empty profile" }
                existing[name] = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        } ?: error("Cannot enumerate MediaStore; grant photo access in the isolated test profile")
        check(existing.isEmpty() || existing.size == assets.size) { "Incomplete fixture; inspect it before retrying" }
        val graph = EntryPoints.get(context.applicationContext, QualityEvaluationEntryPoint::class.java)
        check(graph.storage().catalogDao.allAssets().all { it.displayName in byName }) {
            "Existing index contains non-corpus assets; no reset is performed"
        }
        withContext(Dispatchers.IO) {
            for (asset in assets) {
                val name = asset.getString("id") + ".jpg"
                val relative = asset.getString("file")
                check(Regex("(development|holdout)/[a-z0-9-]+\\.jpg").matches(relative))
                val input = File(root, "input/$relative")
                check(input.inputStream().use(::sha256) == asset.getString("sha256"))
                val prior = existing[name]
                if (prior != null) {
                    check(requireNotNull(resolver.openInputStream(prior)).use(::sha256) == asset.getString("sha256"))
                    continue
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NaytiEval/${asset.getString("split")}/")
                    put(MediaStore.Images.Media.DATE_TAKEN, 1_780_000_000_000L)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(resolver.insert(collection, values))
                try {
                    requireNotNull(resolver.openOutputStream(uri)).use { output -> input.inputStream().use { it.copyTo(output) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (failure: Throwable) {
                    resolver.delete(uri, null, null)
                    throw failure
                }
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            withTimeout(120_000) {
                graph.catalog().state.first { it.status == CatalogRuntimeStatus.Ready && it.summary.available == assets.size.toLong() }
            }
            val packs = graph.packs()
            packs.state.first { it.status != ModelPackRuntimeStatus.Loading }
            packs.install(FileModelPackSource(File(root, "model.naytipack").toPath()))
            val installed = withTimeout(15 * 60_000L) {
                packs.state.first { it.status != ModelPackRuntimeStatus.Installing }
            }
            check(installed.status == ModelPackRuntimeStatus.Ready)
            val pack = checkNotNull(installed.installed)
            check(graph.indexing().setIndexingScope(null))
            val started = withContext(Dispatchers.Main) { graph.controller().start() }
            check(started == IndexingStartResult.Started) { "Foreground start rejected: $started" }
            withTimeout(45 * 60_000L) {
                delay(1_000)
                graph.indexing().state.first {
                    check(it.status !in setOf(OcrIndexingStatus.Failed, OcrIndexingStatus.Waiting, OcrIndexingStatus.Paused)) {
                        "Preparation stopped: ${it.errorCode}; resolve the resource condition, do not bypass it"
                    }
                    it.status == OcrIndexingStatus.Ready && it.capabilities.size == 4 &&
                        it.capabilities.all { channel -> channel.outstanding == 0L }
                }
            }
            val rows = graph.storage().catalogDao.availableAssets()
            check(rows.size == assets.size && rows.all { it.displayName in byName })
            val ids = rows.associate { it.assetId to requireNotNull(it.displayName).removeSuffix(".jpg") }
            val byId = rows.associateBy { ids.getValue(it.assetId) }
            val results = JSONArray()
            for (query in manifest.getJSONArray("queries").objects()) {
                val startedAt = SystemClock.elapsedRealtimeNanos()
                val hits = JSONArray()
                if (query.getString("category") == "duplicates") {
                    val result = graph.duplicates().nearDuplicates(byId.getValue(query.getString("source_asset")).assetId)
                    check(result.status == PerceptualHashSearchStatus.READY)
                    result.hits.forEachIndexed { index, hit ->
                        hits.put(JSONObject().put("id", ids.getValue(hit.assetId)).put("rank", index + 1).put("reason", "NEAR_DUPLICATE"))
                    }
                } else {
                    val selected = query.getJSONArray("channels").let { array -> (0 until array.length()).map(array::getString).toSet() }
                    val split = query.getString("split")
                    val bucketIds = assets.filter { it.getString("split") == split }
                        .map { byId.getValue(it.getString("id")).bucketId }.toSet()
                    check(bucketIds.size == 1 && bucketIds.single() != null)
                    val result = graph.search().search(
                        query.getString("query"), OcrIndexingRuntime.PipelineVersion, pack.manifestSha256,
                        filter = SearchFilter(bucketId = bucketIds.single()),
                        channels = SearchChannelSelection("literal" in selected, "semantic" in selected, "visual" in selected),
                    )
                    check(result.snapshotId != null)
                    result.hits.forEachIndexed { index, hit ->
                        hits.put(JSONObject().put("id", ids.getValue(hit.assetId)).put("rank", index + 1).put("reason", hit.reason.name))
                    }
                }
                results.put(JSONObject().put("query_id", query.getString("id")).put("status", "complete")
                    .put("latency_ms", (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0).put("hits", hits))
            }
            val metadata = JSONObject().put("source_commit", commit).put("pack_manifest_sha256", pack.manifestSha256)
                .put("app_version", BuildConfig.VERSION_NAME).put("android_api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first()).put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("preprocessing", "ocr=${OcrIndexingRuntime.PipelineVersion};engine-api=1;contracts-in-pack")
            val report = JSONObject().put("schema", 1).put("manifest_sha256", manifestHash).put("metadata", metadata)
                .put("execution", "production-mediastore-index-query").put("results", results)
            File(root, "run.json").writeText(report.toString(2))
        } finally {
            graph.indexing().requestSystemStop()
            scenario.close()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
