package app.nayti.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.search.engine.VectorSegmentV1Reader
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexWorkState
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.StorageContract
import app.nayti.storage.VectorGenerationEntity
import app.nayti.storage.VectorGenerationState
import app.nayti.storage.VectorPublicationState
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorPublicationStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.filesDir.resolve("production-vector-index-test")
    private lateinit var storage: CatalogStorage
    private var now = System.currentTimeMillis()

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(root.deleteRecursively())
        storage = CatalogStorage.open(context)
        storage.catalogDao.recordAccessObservation("Full", AccessRevision, now)
        storage.modelPackDao.registerInstalledCandidate(pack())
        storage.vectorIndexDao.createGeneration(generation())
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(StorageContract.DatabaseFileName)
        check(root.deleteRecursively())
    }

    @Test
    fun oneHundredCrashBoundariesNeverExposePartialSnapshot() = runBlocking {
        repeat(CrashIterations) { iteration ->
            now += 100
            val assetId = insertAsset(iteration + 1L)
            val lease = stageRunningWork(assetId, "lease-$iteration")
            val boundary = Boundaries[iteration % Boundaries.size]
            val previousActive = storage.vectorIndexDao.activeSnapshotId()
            val store = store { actual -> if (actual == boundary) throw SimulatedProcessDeath(actual) }
            val failure = runCatching {
                store.publish(request(iteration, assetId, lease))
            }.exceptionOrNull()
            assertTrue(failure is SimulatedProcessDeath)

            reopenStorage()
            val report = VectorIndexRecovery(root, storage.vectorIndexDao).recover(
                nowMillis = now + 20_000,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
            storage.indexStateDao.recoverExpiredExecution(now + 20_000)
            val expected = if (boundary == VectorPublicationBoundary.AFTER_DB_COMMIT) "snapshot-$iteration" else previousActive
            assertEquals(expected, report.activeAfter)
            assertEquals(expected, storage.vectorIndexDao.activeSnapshotId())
            assertEquals(0, root.resolve("staging").listFiles().orEmpty().count { it.extension == "tmp" })
            val publication = storage.vectorIndexDao.publication("publication-$iteration")
            if (boundary == VectorPublicationBoundary.AFTER_DB_COMMIT) {
                assertEquals(VectorPublicationState.DONE, publication?.state)
                assertEquals(IndexWorkState.DONE, storage.indexStateDao.work(assetId, IndexChannel.VISUAL)?.state)
            } else if (publication != null) {
                assertEquals(VectorPublicationState.ABANDONED, publication.state)
            }

            val second = VectorIndexRecovery(root, storage.vectorIndexDao).recover(
                nowMillis = now + 20_001,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
            assertEquals(expected, second.activeAfter)
            assertEquals(0, second.abandonedPublications)
            assertEquals(0, second.deletedTemps)
            assertEquals(0, second.deletedOrphans)
        }
    }

    @Test
    fun corruptChildRollsBackAndQueryLeasePinsExactActiveSnapshot() = runBlocking {
        val firstAsset = insertAsset(1)
        val first = store().publish(request(1, firstAsset, stageRunningWork(firstAsset, "lease-parent")))
        now += 100
        val secondAsset = insertAsset(2)
        val child = store().publish(request(2, secondAsset, stageRunningWork(secondAsset, "lease-child")))
        assertEquals(first.snapshotId, child.parentSnapshotId)

        storage.vectorIndexDao.acquireActiveSnapshotLease(
            QuerySnapshotLeaseEntity(
                leaseToken = "query-lease",
                snapshotId = child.snapshotId,
                accessRevision = AccessRevision,
                createdAtMillis = now,
                expiresAtMillis = now + 1_000,
            ),
            now,
        )
        assertNotNull(storage.vectorIndexDao.queryLease("query-lease"))

        val childManifest = checkNotNull(storage.vectorIndexDao.manifest(child.visualManifestRevision!!))
        val childSegment = storage.vectorIndexDao.manifestSegments(childManifest.revision).last()
        val artifact = checkNotNull(storage.vectorIndexDao.segment(childSegment.segmentSha256))
        val file = root.resolve(artifact.relativePath)
        check(file.setWritable(true, true))
        RandomAccessFile(file, "rw").use { random ->
            random.seek(random.length() - 1)
            val value = random.read()
            random.seek(random.length() - 1)
            random.write(value xor 0xff)
            random.fd.sync()
        }

        reopenStorage()
        val recovery = VectorIndexRecovery(root, storage.vectorIndexDao).recover(
            nowMillis = now + 2_000,
            orphanGraceMillis = 60_000,
            deepVerifySegments = true,
        )
        assertEquals(child.snapshotId, recovery.activeBefore)
        assertEquals(first.snapshotId, recovery.activeAfter)
        assertEquals(1, recovery.expiredQueryLeases)
        assertNull(storage.vectorIndexDao.queryLease("query-lease"))
    }

    @Test
    fun gcPinsLiveAndRollbackSnapshotsAndReplaysDeleteIntent() = runBlocking {
        val firstAsset = insertAsset(1)
        val first = store().publish(request(201, firstAsset, stageRunningWork(firstAsset, "lease-201")))
        storage.vectorIndexDao.acquireActiveSnapshotLease(
            QuerySnapshotLeaseEntity(
                leaseToken = "old-query",
                snapshotId = first.snapshotId,
                accessRevision = AccessRevision,
                createdAtMillis = now,
                expiresAtMillis = now + 10_000,
            ),
            now,
        )
        now += 100
        val secondAsset = insertAsset(2)
        val second = store().publish(request(202, secondAsset, stageRunningWork(secondAsset, "lease-202")))
        val gc = VectorSnapshotGarbageCollector(root, storage.vectorIndexDao)
        assertFalse(gc.collect(first.snapshotId, now))

        now += 20_000
        storage.vectorIndexDao.expireQueryLeases(now)
        assertFalse(gc.collect(first.snapshotId, now))
        val thirdAsset = insertAsset(3)
        val third = store().publish(request(203, thirdAsset, stageRunningWork(thirdAsset, "lease-203")))
        assertEquals(second.snapshotId, third.parentSnapshotId)

        val failure = runCatching {
            VectorSnapshotGarbageCollector(root, storage.vectorIndexDao) { boundary ->
                if (boundary == VectorGcBoundary.AFTER_FIRST_UNLINK) throw SimulatedGcDeath()
            }.collect(first.snapshotId, now)
        }.exceptionOrNull()
        assertTrue(failure is SimulatedGcDeath)
        assertNotNull(storage.vectorIndexDao.snapshot(first.snapshotId))

        reopenStorage()
        val recovery = VectorIndexRecovery(root, storage.vectorIndexDao).recover(
            nowMillis = now,
            orphanGraceMillis = 60_000,
            deepVerifySegments = true,
        )
        assertEquals(0, recovery.replayedDeleteIntents)
        assertNull(storage.vectorIndexDao.snapshot(first.snapshotId))
        assertEquals(third.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        val activeManifest = checkNotNull(storage.vectorIndexDao.manifest(third.visualManifestRevision!!))
        assertEquals(3, storage.vectorIndexDao.manifestSegments(activeManifest.revision).size)
        assertTrue(storage.vectorIndexDao.manifestSegments(activeManifest.revision).all { entry ->
            val artifact = checkNotNull(storage.vectorIndexDao.segment(entry.segmentSha256))
            root.resolve(artifact.relativePath).isFile
        })
    }

    @Test
    fun sealedGenerationCompactsAdjacentSegmentsWithoutInPlaceRewrite() = runBlocking {
        var active = publishSingle(301, 1)
        active = publishSingle(302, 2)
        active = publishSingle(303, 3)
        val originalRevision = checkNotNull(active.visualManifestRevision)
        val originalEntries = storage.vectorIndexDao.manifestSegments(originalRevision)
        assertEquals(3, originalEntries.size)
        storage.vectorIndexDao.acquireActiveSnapshotLease(
            QuerySnapshotLeaseEntity(
                leaseToken = "compaction-query",
                snapshotId = active.snapshotId,
                accessRevision = AccessRevision,
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            ),
            now,
        )
        storage.vectorIndexDao.sealGeneration(GenerationId, originalRevision, now)
        assertEquals(VectorGenerationState.SEALED, storage.vectorIndexDao.generation(GenerationId)?.state)

        val failed = runCatching {
            VectorCompactionStore(
                rootDirectory = root,
                dao = storage.vectorIndexDao,
                nowMillis = { now },
                boundaryObserver = { boundary ->
                    if (boundary == VectorCompactionBoundary.AFTER_MANIFEST_RENAME) {
                        throw SimulatedCompactionDeath()
                    }
                },
            ).compact(compactionRequest("failed-compaction", "compact-failed", "compact-failed-snapshot"))
        }.exceptionOrNull()
        assertTrue(failed is SimulatedCompactionDeath)
        assertEquals(active.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        reopenStorage()
        val recovery = VectorIndexRecovery(root, storage.vectorIndexDao).recover(now + 1_000, 0, true)
        assertEquals(active.snapshotId, recovery.activeAfter)
        assertTrue(recovery.deletedOrphans >= 2)

        val compacted = VectorCompactionStore(root, storage.vectorIndexDao, { now }).compact(
            compactionRequest("compact-success", "compact-r1", "compact-snapshot"),
        )
        val compactedManifest = checkNotNull(storage.vectorIndexDao.manifest(compacted.visualManifestRevision!!))
        val compactedEntries = storage.vectorIndexDao.manifestSegments(compactedManifest.revision)
        assertEquals(active.snapshotId, compacted.parentSnapshotId)
        assertEquals(active.snapshotId, storage.vectorIndexDao.queryLease("compaction-query")?.snapshotId)
        assertEquals(originalEntries.size - 1, compactedEntries.size)
        assertEquals(3L, compactedManifest.recordCount)
        val merged = checkNotNull(storage.vectorIndexDao.segment(compactedEntries.first().segmentSha256))
        assertEquals(2, merged.recordCount)
        assertEquals(2, storage.vectorIndexDao.segmentRecords(merged.sha256).size)
        assertTrue(root.resolve(merged.relativePath).isFile)
        assertTrue(originalEntries.all { entry ->
            val artifact = checkNotNull(storage.vectorIndexDao.segment(entry.segmentSha256))
            root.resolve(artifact.relativePath).isFile
        })
    }

    @Test
    fun filesystemFailuresRemainInvisibleAndRecoverable() = runBlocking {
        val faultPoints = VectorArtifactRole.entries.flatMap { role -> VectorFileOperation.entries.map { role to it } }
        faultPoints.forEachIndexed { iteration, (failingRole, failingOperation) ->
            now += 100
            val assetId = insertAsset(iteration + 1L)
            val lease = stageRunningWork(assetId, "io-lease-$iteration")
            var injected = false
            val failure = runCatching {
                VectorPublicationStore(
                    rootDirectory = root,
                    dao = storage.vectorIndexDao,
                    nowMillis = { now },
                    fileFaultInjector = { role, operation ->
                        if (!injected && role == failingRole && operation == failingOperation) {
                            injected = true
                            throw IOException("Injected ENOSPC at $role/$operation")
                        }
                    },
                ).publish(request(401 + iteration, assetId, lease))
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertNull(storage.vectorIndexDao.activeSnapshotId())

            reopenStorage()
            val report = IndexStartupRecovery(root, storage.indexStateDao, storage.vectorIndexDao).recover(
                nowMillis = now + 20_000,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
            assertNull(report.vector.activeAfter)
            assertEquals(0, root.resolve("staging").listFiles().orEmpty().count { it.extension == "tmp" })
            assertEquals(0, root.resolve("segments").listFiles().orEmpty().size)
            assertEquals(0, root.resolve("manifests").listFiles().orEmpty().size)
        }
    }

    @Test
    fun expiredLeaseIsTypedRejectionAndSealedOrphanIsRecoverable() = runBlocking {
        val assetId = insertAsset(999)
        val lease = stageRunningWork(assetId, "expired-lease")
        now += 20_000

        val failure = runCatching { store().publish(request(999, assetId, lease)) }.exceptionOrNull()

        assertTrue(failure is VectorPublicationLeaseRejectedException)
        assertNull(storage.vectorIndexDao.publication("publication-999"))
        assertNull(storage.vectorIndexDao.activeSnapshotId())
        assertEquals(1, root.resolve("segments").listFiles().orEmpty().size)
        val recovery =
            IndexStartupRecovery(root, storage.indexStateDao, storage.vectorIndexDao).recover(
                nowMillis = now,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
        assertEquals(1, recovery.vector.deletedOrphans)
        assertTrue(root.resolve("segments").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun incompatibleWorkContractIsRejectedBeforeDatabaseStaging() = runBlocking {
        val assetId = insertAsset(1_000)
        val lease = stageRunningWork(assetId, "wrong-contract")
        val work = checkNotNull(storage.indexStateDao.work(assetId, IndexChannel.VISUAL))
        storage.indexStateDao.replaceWork(work.copy(componentHash = SecondEmbeddingHash))

        val failure = runCatching { store().publish(request(1_000, assetId, lease)) }.exceptionOrNull()

        assertTrue(failure is VectorPublicationLeaseRejectedException)
        assertNull(storage.vectorIndexDao.publication("publication-1000"))
        assertNull(storage.vectorIndexDao.activeSnapshotId())
        assertEquals(IndexWorkState.RUNNING, storage.indexStateDao.work(assetId, IndexChannel.VISUAL)?.state)
        val recovery =
            IndexStartupRecovery(root, storage.indexStateDao, storage.vectorIndexDao).recover(
                nowMillis = now,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
        assertEquals(1, recovery.vector.deletedOrphans)
    }

    @Test
    fun newEmbeddingGenerationStartsFreshManifestFromActiveSnapshot() = runBlocking {
        val firstAsset = insertAsset(1)
        val first = store().publish(request(1_001, firstAsset, stageRunningWork(firstAsset, "first-generation")))
        val secondGeneration = generation().copy(
            generationId = SecondGenerationId,
            embeddingSpaceHash = SecondEmbeddingHash,
        )
        storage.vectorIndexDao.createGeneration(secondGeneration)
        now += 100
        val secondAsset = insertAsset(2)
        val second =
            store().publish(
                request(1_002, secondAsset, stageRunningWork(secondAsset, "second-generation")).copy(
                    generationId = SecondGenerationId,
                ),
            )

        assertEquals(first.snapshotId, second.parentSnapshotId)
        val manifest = checkNotNull(storage.vectorIndexDao.manifest(checkNotNull(second.visualManifestRevision)))
        assertNull(manifest.parentRevision)
        assertEquals(SecondGenerationId, manifest.generationId)
        assertEquals(1, storage.vectorIndexDao.manifestSegments(manifest.revision).size)
    }

    @Test
    fun imageToImageSearchUsesCurrentEligibleVectorsAndReleasesLease() = runBlocking {
        val sourceAsset = insertAsset(1)
        store().publish(
            request(2_001, sourceAsset, stageRunningWork(sourceAsset, "visual-source")).withVector(100),
        )
        now += 100
        val similarAsset = insertAsset(2)
        store().publish(
            request(2_002, similarAsset, stageRunningWork(similarAsset, "visual-similar")).withVector(100),
        )
        now += 100
        val differentAsset = insertAsset(3)
        store().publish(
            request(2_003, differentAsset, stageRunningWork(differentAsset, "visual-different")).withVector(-100),
        )
        val search =
            VisualSimilaritySearch(
                vectors = storage.vectorIndexDao,
                vectorRoot = root,
                clock = { now },
                leaseTokens = { "visual-query-test" },
            )

        val initial = search.searchSimilar(sourceAsset)

        assertEquals(VisualSimilaritySearchStatus.READY, initial.status)
        assertEquals(listOf(similarAsset, differentAsset), initial.hits.map { it.assetId })
        assertTrue(initial.hits.first().rawScore > initial.hits.last().rawScore)
        assertNull(storage.vectorIndexDao.queryLease("visual-query-test"))

        val changed = checkNotNull(storage.catalogDao.asset(similarAsset))
        assertEquals(1, storage.catalogDao.updateAsset(changed.copy(sourceFingerprint = "changed-source")))
        val afterSourceChange = search.searchSimilar(sourceAsset)
        assertEquals(listOf(differentAsset), afterSourceChange.hits.map { it.assetId })

        storage.catalogDao.recordAccessObservation("Full", AccessRevision + 1, now + 1)
        val afterAccessChange = search.searchSimilar(sourceAsset)
        assertEquals(VisualSimilaritySearchStatus.SOURCE_NOT_INDEXED, afterAccessChange.status)
        assertTrue(afterAccessChange.hits.isEmpty())
        assertNull(storage.vectorIndexDao.queryLease("visual-query-test"))
    }

    @Test
    fun visualCompactionKeepsNewestRecordWhenOneAssetWasReindexed() = runBlocking {
        val assetId = insertAsset(1)
        repeat(8) { index ->
            now += 100
            val iteration = 3_100 + index
            store().publish(
                request(iteration, assetId, stageRunningWork(assetId, "reindex-$index"))
                    .withVector(index + 1),
            )
        }

        assertEquals(
            1,
            VectorIndexCompactor(root, storage.vectorIndexDao, nowMillis = { now })
                .compactAvailable(1, IndexChannel.VISUAL),
        )

        val active = checkNotNull(storage.vectorIndexDao.activeSnapshotId()).let { id ->
            checkNotNull(storage.vectorIndexDao.snapshot(id))
        }
        val manifest = checkNotNull(active.visualManifestRevision).let { revision ->
            checkNotNull(storage.vectorIndexDao.manifest(revision))
        }
        assertEquals(1L, manifest.recordCount)
        val artifact = storage.vectorIndexDao.manifestSegments(manifest.revision).single().let { entry ->
            checkNotNull(storage.vectorIndexDao.segment(entry.segmentSha256))
        }
        assertEquals(1, artifact.compactionLevel)
        val decoded = VectorSegmentV1Reader.decode(root.resolve(artifact.relativePath).readBytes())
        assertEquals(assetId, decoded.records.single().assetId)
        assertTrue(decoded.records.single().vector.all { value -> value == 8.toByte() })
    }

    private fun store(observer: (VectorPublicationBoundary) -> Unit = {}) =
        VectorPublicationStore(root, storage.vectorIndexDao, { now }, observer)

    private suspend fun publishSingle(iteration: Int, mediaStoreId: Long): app.nayti.storage.ActivationSnapshotEntity {
        now += 100
        val assetId = insertAsset(mediaStoreId)
        return store().publish(request(iteration, assetId, stageRunningWork(assetId, "lease-$iteration")))
    }

    private fun compactionRequest(token: String, revision: String, snapshotId: String) =
        VectorCompactionRequest(
            compactionToken = token,
            generationId = GenerationId,
            firstSegmentOrdinal = 0,
            segmentCount = 2,
            manifestRevision = revision,
            snapshotId = snapshotId,
            rankingConfigVersion = "ranking-v1",
            lexicalPublicationEpoch = 303,
            pHashPublicationEpoch = 303,
            catalogWatermark = 303,
            segmentId = UUID.nameUUIDFromBytes("$token-segment".encodeToByteArray()),
        )

    private fun request(iteration: Int, assetId: Long, leaseToken: String) =
        VectorPublicationRequest(
            publicationToken = "publication-$iteration",
            generationId = GenerationId,
            manifestRevision = "manifest-$iteration",
            snapshotId = "snapshot-$iteration",
            leaseTokens = listOf(leaseToken),
            records = listOf(
                PublishedVectorRecord(
                    recordId = assetId,
                    assetId = assetId,
                    chunkOrdinal = 0,
                    sourceFingerprint = "source-$assetId",
                    vector = ByteArray(Dimension) { index -> ((assetId + index) % 127).toByte() },
                ),
            ),
            rankingConfigVersion = "ranking-v1",
            lexicalPublicationEpoch = iteration.toLong(),
            pHashPublicationEpoch = iteration.toLong(),
            catalogWatermark = iteration.toLong(),
            segmentId = UUID.nameUUIDFromBytes("segment-$iteration".encodeToByteArray()),
        )

    private fun VectorPublicationRequest.withVector(value: Int): VectorPublicationRequest =
        copy(
            records = records.map { record -> record.copy(vector = ByteArray(Dimension) { value.toByte() }) },
        )

    private suspend fun insertAsset(mediaStoreId: Long): Long =
        storage.catalogDao.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = mediaStoreId,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 10,
                height = 10,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = mediaStoreId,
                dateTakenMillis = null,
                dateModifiedSeconds = mediaStoreId,
                displayName = null,
                bucketId = null,
                bucketDisplayName = null,
                relativePath = null,
                sourceFingerprint = "source-$mediaStoreId",
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = now,
            ),
        )

    private suspend fun stageRunningWork(assetId: Long, leaseToken: String): String {
        storage.indexStateDao.replaceWork(
            IndexChannelWorkEntity(
                assetId = assetId,
                channel = IndexChannel.VISUAL,
                state = IndexWorkState.RUNNING,
                sourceFingerprint = "source-$assetId",
                accessRevision = AccessRevision,
                pipelineVersion = "visual-v1",
                componentHash = ComponentHash,
                attempt = 1,
                leaseToken = leaseToken,
                leaseExpiresAtMillis = now + 10_000,
                executionWindowId = null,
                publicationToken = null,
                stagedArtifactPath = null,
                stagedArtifactLength = null,
                stagedArtifactSha256 = null,
                nextEligibleAtMillis = null,
                errorCode = null,
                updatedAtMillis = now,
            ),
        )
        return leaseToken
    }

    private fun reopenStorage() {
        storage.close()
        storage = CatalogStorage.open(context)
    }

    private fun pack() =
        ModelPackEntity(
            packId = PackId,
            packVersion = PackVersion,
            keyId = "test-key",
            manifestSha256 = PackManifestHash,
            relativeDirectory = "model-packs/test",
            payloadBytes = 1,
            installedAtMillis = now,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private fun generation() =
        VectorGenerationEntity(
            generationId = GenerationId,
            channel = IndexChannel.VISUAL,
            packId = PackId,
            packVersion = PackVersion,
            pipelineVersion = "visual-v1",
            componentHash = ComponentHash,
            embeddingSpaceHash = EmbeddingHash,
            dimension = Dimension,
            state = VectorGenerationState.BUILDING,
            createdAtMillis = now,
            sealedAtMillis = null,
        )

    private class SimulatedProcessDeath(val boundary: VectorPublicationBoundary) : RuntimeException()
    private class SimulatedGcDeath : RuntimeException()
    private class SimulatedCompactionDeath : RuntimeException()

    private companion object {
        const val AccessRevision = 7L
        const val PackId = "nayti-offline-search"
        const val PackVersion = "0.1.0-alpha.1"
        const val GenerationId = "visual-generation-1"
        const val SecondGenerationId = "visual-generation-2"
        const val Dimension = 8
        const val ComponentHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val EmbeddingHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SecondEmbeddingHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val PackManifestHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val CrashIterations = 100
        val Boundaries = VectorPublicationBoundary.entries
    }
}
