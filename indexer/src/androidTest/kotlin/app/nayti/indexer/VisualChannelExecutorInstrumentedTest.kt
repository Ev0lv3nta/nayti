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
import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.platform.media.AndroidMediaStoreGateway
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.MediaPermissions
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexWorkState
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.StorageContract
import app.nayti.storage.VectorGenerationEntity
import app.nayti.storage.VectorGenerationState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualChannelExecutorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val vectorRoot: File = context.filesDir.resolve("visual-executor-test")
    private val createdUris = mutableListOf<Uri>()
    private lateinit var storage: CatalogStorage

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(vectorRoot.deleteRecursively())
        storage = CatalogStorage.open(context)
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MediaPermissions.ReadImages
            } else {
                MediaPermissions.ReadExternalStorage
            }
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity(permission)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, Now)
        storage.modelPackDao.registerInstalledCandidate(pack())
        storage.vectorIndexDao.createGeneration(generation())
    }

    @After
    fun tearDown() {
        storage.close()
        createdUris.forEach { resolver.delete(it, null, null) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(vectorRoot.deleteRecursively())
    }

    @Test
    fun currentImagePublishesSingleDurableVisualVector() = runBlocking {
        val uri = insertMedia(validJpeg = true)
        insertCatalogAsset(uri, FingerprintA)
        val engine = FixedVisualEngine()
        val coordinator = coordinator(engine)
        val operation = coordinator.planOperation(request("success"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.published)
        assertEquals(1, engine.calls)
        assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(1, IndexChannel.VISUAL)?.state)
        val snapshot = checkNotNull(storage.vectorIndexDao.activeSnapshotId()).let { id ->
            checkNotNull(storage.vectorIndexDao.snapshot(id))
        }
        val manifest = checkNotNull(snapshot.visualManifestRevision).let { revision ->
            checkNotNull(storage.vectorIndexDao.manifest(revision))
        }
        val segment = storage.vectorIndexDao.manifestSegments(manifest.revision).single().let { entry ->
            checkNotNull(storage.vectorIndexDao.segment(entry.segmentSha256))
        }
        val record = storage.vectorIndexDao.segmentRecords(segment.sha256).single()
        assertEquals(1L, record.recordId)
        assertEquals(1L, record.assetId)
        assertEquals(0, record.chunkOrdinal)
        assertNull(record.semanticChunkId)
        assertEquals(Siglip2Contract.EmbeddingDimension, segment.dimension)
        assertEquals(IndexChannel.VISUAL, manifest.channel)
    }

    @Test
    fun corruptImageBecomesPermanentVisualGap() = runBlocking {
        val uri = insertMedia(validJpeg = false)
        insertCatalogAsset(uri, FingerprintB)
        val coordinator = coordinator(FixedVisualEngine())
        val operation = coordinator.planOperation(request("corrupt"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.permanentFailures)
        val work = checkNotNull(storage.indexStateDao.work(1, IndexChannel.VISUAL))
        assertEquals(IndexWorkState.PERMANENT_ERROR, work.state)
        assertEquals(VisualChannelExecutor.ErrorCorruptMedia, work.errorCode)
        assertNull(storage.vectorIndexDao.activeSnapshotId())
    }

    @Test
    fun imageDeletedAfterClaimCannotPublish() = runBlocking {
        val uri = insertMedia(validJpeg = true)
        insertCatalogAsset(uri, FingerprintC)
        val coordinator = coordinator(FixedVisualEngine())
        val operation = coordinator.planOperation(request("access"))
        val window = coordinator.startExecutionWindow(operation.operationId, "TEST", 60_000)
        assertEquals(1, resolver.delete(uri, null, null))
        createdUris.remove(uri)

        val report = coordinator.runWindow(window.windowId)

        assertEquals(1, report.leaseRejections)
        assertNull(storage.vectorIndexDao.activeSnapshotId())
        assertTrue(vectorRoot.resolve("segments").listFiles().orEmpty().isEmpty())
    }

    private fun coordinator(engine: VisualEmbeddingEngine): IndexExecutionCoordinator =
        IndexExecutionCoordinator(
            indexState = storage.indexStateDao,
            catalog = storage.catalogDao,
            executors =
                mapOf(
                    IndexChannel.VISUAL to
                        VisualChannelExecutor(
                            indexState = storage.indexStateDao,
                            semantic = storage.ocrSemanticDao,
                            hashes = storage.perceptualHashDao,
                            decoder =
                                BoundedMediaDecoder(
                                    resolver,
                                    AndroidMediaStoreGateway(context),
                                ),
                            embedding = engine,
                            publisher =
                                VectorStoreVisualPublisher(
                                    VectorPublicationStore(
                                        vectorRoot,
                                        storage.vectorIndexDao,
                                        nowMillis = { Now },
                                    ),
                                ),
                            generationId = GenerationId,
                            componentHash = ComponentHash,
                            clock = OcrExecutorClock { Now },
                        ),
                ),
            ids = SequentialIds(),
        )

    private fun request(suffix: String) =
        IndexOperationRequest(
            operationId = "visual-executor-$suffix",
            profileId = "balanced-v1",
            targetPackId = PackId,
            targetPackVersion = PackVersion,
            channels =
                listOf(
                    IndexChannelContract(
                        IndexChannel.VISUAL,
                        0,
                        Siglip2Contract.PipelineVersion,
                        ComponentHash,
                    ),
                ),
            autoResume = true,
        )

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
                displayName = "visual-executor-test.jpg",
                bucketId = null,
                bucketDisplayName = null,
                relativePath = "Pictures/NaytiTests",
                sourceFingerprint = fingerprint,
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = Now,
            ),
        )
    }

    private fun insertMedia(validJpeg: Boolean): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "nayti-visual-${UUID.randomUUID()}.jpg")
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

    private fun pack() =
        ModelPackEntity(
            packId = PackId,
            packVersion = PackVersion,
            keyId = "test-key",
            manifestSha256 = PackManifestHash,
            relativeDirectory = "model-packs/test",
            payloadBytes = 1,
            installedAtMillis = Now,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private fun generation() =
        VectorGenerationEntity(
            generationId = GenerationId,
            channel = IndexChannel.VISUAL,
            packId = PackId,
            packVersion = PackVersion,
            pipelineVersion = Siglip2Contract.PipelineVersion,
            componentHash = ComponentHash,
            embeddingSpaceHash = EmbeddingHash,
            dimension = Siglip2Contract.EmbeddingDimension,
            state = VectorGenerationState.BUILDING,
            createdAtMillis = Now,
            sealedAtMillis = null,
        )

    private class FixedVisualEngine : VisualEmbeddingEngine {
        var calls = 0

        override fun encodeImage(bitmap: Bitmap): ByteArray {
            assertTrue(bitmap.width <= Siglip2Contract.DecodeLongSide)
            assertTrue(bitmap.height <= Siglip2Contract.DecodeLongSide)
            calls += 1
            return ByteArray(Siglip2Contract.EmbeddingDimension) { index -> (index % 127).toByte() }
        }
    }

    private class SequentialIds : IndexIdFactory {
        private var next = 0

        override fun create(purpose: String): String = "$purpose-${++next}"
    }

    private companion object {
        const val Now = 10_000L
        const val AccessRevision = 7L
        const val PackId = "nayti-offline-search"
        const val PackVersion = "0.1.0-alpha.2"
        const val GenerationId = "visual-generation-test"
        const val ComponentHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EmbeddingHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PackManifestHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val FingerprintA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val FingerprintB = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val FingerprintC = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}
