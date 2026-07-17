package app.nayti.indexer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.ml.runtime.ocr.OcrInferenceEngine
import app.nayti.ml.runtime.ocr.OcrPoint
import app.nayti.ml.runtime.ocr.OcrQuadrilateral
import app.nayti.ml.runtime.ocr.RecognizedOcrRegion
import app.nayti.platform.media.AndroidMediaStoreGateway
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.MediaPermissions
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexWorkState
import app.nayti.storage.StorageContract
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrChannelExecutorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val createdUris = mutableListOf<Uri>()
    private lateinit var storage: CatalogStorage

    @Before
    fun setUp() {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        storage = CatalogStorage.open(context)
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MediaPermissions.ReadImages
            } else {
                MediaPermissions.ReadExternalStorage
            }
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity(permission)
    }

    @After
    fun tearDown() {
        storage.close()
        createdUris.forEach { resolver.delete(it, null, null) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        context.deleteDatabase(StorageContract.DatabaseFileName)
    }

    @Test
    fun realMediaDecodePublishesAtomicSearchableOcr() = runBlocking {
        val uri = insertMedia(validJpeg = true)
        insertCatalogAsset(uri, FingerprintA)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, System.currentTimeMillis())
        val coordinator = coordinator(fixedInference("Счёт ABC-123"))
        val operation = coordinator.planOperation(request("success"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.published)
        assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(1, IndexChannel.OCR)?.state)
        val document = checkNotNull(storage.ocrDao.document(1))
        assertEquals("счет abc 123", document.canonicalText)
        assertEquals(1, document.regionCount)
        val hits = OcrLexicalSearch(storage.ocrDao).search("ABC-123", PipelineVersion, ComponentHash).hits
        assertEquals(listOf(1L), hits.map(OcrLexicalHit::assetId))
    }

    @Test
    fun corruptMediaBecomesPermanentVisibleGap() = runBlocking {
        val uri = insertMedia(validJpeg = false)
        insertCatalogAsset(uri, FingerprintB)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, System.currentTimeMillis())
        val coordinator = coordinator(fixedInference("unused"))
        val operation = coordinator.planOperation(request("corrupt"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.permanentFailures)
        val work = checkNotNull(storage.indexStateDao.work(1, IndexChannel.OCR))
        assertEquals(IndexWorkState.PERMANENT_ERROR, work.state)
        assertEquals(OcrChannelExecutor.ErrorCorruptMedia, work.errorCode)
        assertTrue(storage.ocrDao.document(1) == null)
    }

    @Test
    fun mediaDeletedAfterClaimCannotPublish() = runBlocking {
        val uri = insertMedia(validJpeg = true)
        insertCatalogAsset(uri, FingerprintC)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, System.currentTimeMillis())
        val coordinator = coordinator(fixedInference("stale"))
        val operation = coordinator.planOperation(request("access"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)
        assertEquals(1, resolver.delete(uri, null, null))
        createdUris.remove(uri)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.leaseRejections)
        assertTrue(storage.ocrDao.document(1) == null)
    }

    private fun coordinator(inference: OcrInferenceEngine): IndexExecutionCoordinator {
        val executor =
            OcrChannelExecutor(
                ocr = storage.ocrDao,
                decoder = BoundedMediaDecoder(resolver, AndroidMediaStoreGateway(context)),
                inference = inference,
            )
        return IndexExecutionCoordinator(
            indexState = storage.indexStateDao,
            catalog = storage.catalogDao,
            executors = mapOf(IndexChannel.OCR to executor),
            ids = SequentialIds(),
        )
    }

    private fun request(suffix: String) =
        IndexOperationRequest(
            operationId = "ocr-executor-$suffix",
            profileId = "balanced-v1",
            targetPackId = "nayti-offline-search",
            targetPackVersion = "0.1.0-alpha.2",
            channels = listOf(IndexChannelContract(IndexChannel.OCR, 0, PipelineVersion, ComponentHash)),
            autoResume = true,
        )

    private fun fixedInference(text: String) =
        OcrInferenceEngine { bitmap ->
            listOf(
                RecognizedOcrRegion(
                    quadrilateral =
                        OcrQuadrilateral(
                            OcrPoint(0f, 0f),
                            OcrPoint(bitmap.width.toFloat(), 0f),
                            OcrPoint(bitmap.width.toFloat(), bitmap.height.toFloat()),
                            OcrPoint(0f, bitmap.height.toFloat()),
                        ),
                    rawText = text,
                    confidence = 0.95f,
                ),
            )
        }

    private suspend fun insertCatalogAsset(uri: Uri, fingerprint: String) {
        storage.catalogDao.insertAsset(
            CatalogAssetEntity(
                volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                mediaStoreId = checkNotNull(uri.lastPathSegment).toLong(),
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 96,
                height = 48,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = 1,
                dateTakenMillis = null,
                dateModifiedSeconds = 1,
                displayName = "executor-test.jpg",
                bucketId = null,
                bucketDisplayName = null,
                relativePath = "Pictures/NaytiTests",
                sourceFingerprint = fingerprint,
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = 1,
            ),
        )
    }

    private fun insertMedia(validJpeg: Boolean): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "nayti-executor-${UUID.randomUUID()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NaytiTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values))
        createdUris += uri
        resolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output)
            if (validJpeg) {
                val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                } finally {
                    bitmap.recycle()
                }
            } else {
                output.write("not an image".encodeToByteArray())
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        assertEquals(1, resolver.update(uri, values, null, null))
        return uri
    }

    private class SequentialIds : IndexIdFactory {
        private var next = 0

        override fun create(purpose: String): String = "$purpose-${++next}"
    }

    private companion object {
        const val AccessRevision = 7L
        const val PipelineVersion = "ocr-v1"
        const val ComponentHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val FingerprintA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FingerprintB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val FingerprintC = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
