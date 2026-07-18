package app.nayti.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.search.engine.fusion.MultimodalQueryIntent
import app.nayti.search.engine.VectorSegmentV1Reader
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationCandidateChannelAction
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.CatalogWatermarkEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexWorkState
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.OcrDocumentDraft
import app.nayti.storage.OcrPublicationCodec
import app.nayti.storage.OcrRegionDraft
import app.nayti.storage.PerceptualHashCodec
import app.nayti.storage.PerceptualHashDraft
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
    fun readyCandidateActivatesAtomicallyWhileParentLeaseRemainsValidAndRollbackIsPointerOnly() = runBlocking {
        val assetId = insertAsset(1)
        val parent = store().publish(request(101, assetId, stageRunningWork(assetId, "activation-parent")))
        now += 100
        val activator = activator()
        val candidate = activator.register("candidate-101", "candidate-snapshot-101", pack())
        val persistedPlan = storage.vectorIndexDao.activationCandidateChannels(candidate.candidateId)
        assertEquals(storage.vectorIndexDao.snapshotChannels(parent.snapshotId).size, persistedPlan.size)
        assertTrue(persistedPlan.all { it.action == ActivationCandidateChannelAction.INHERIT })
        val child =
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                capturedAccessRevision = AccessRevision,
                catalogWatermark = candidate.capturedCatalogWatermark,
                createdAtMillis = now,
            )
        activator.markReady(candidate.candidateId, child)
        assertFalse(VectorSnapshotGarbageCollector(root, storage.vectorIndexDao).collect(child.snapshotId, now))
        storage.vectorIndexDao.acquireActiveSnapshotLease(
            QuerySnapshotLeaseEntity(
                leaseToken = "parent-query",
                snapshotId = parent.snapshotId,
                accessRevision = AccessRevision,
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            ),
            now,
        )
        storage.vectorIndexDao.acquireActiveSnapshotLease(
            QuerySnapshotLeaseEntity(
                leaseToken = "parent-query-2",
                snapshotId = parent.snapshotId,
                accessRevision = AccessRevision,
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            ),
            now,
        )

        val activated = activator.activate(candidate.candidateId)
        val newQuery =
            checkNotNull(
                storage.vectorIndexDao.acquireCurrentSnapshotLease(
                    leaseToken = "child-query",
                    nowMillis = now,
                    expiresAtMillis = now + 60_000,
                ),
            )

        assertEquals(child.snapshotId, activated.snapshotId)
        assertEquals(parent.snapshotId, activated.rollbackSnapshotId)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.queryLease("parent-query")?.snapshotId)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.queryLease("parent-query-2")?.snapshotId)
        assertEquals(child.snapshotId, newQuery.snapshotId)
        assertFalse(VectorSnapshotGarbageCollector(root, storage.vectorIndexDao).collect(parent.snapshotId, now))
        assertEquals(ActivationCandidateState.ACTIVE, storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state)

        val rolledBack = checkNotNull(activator.rollback())
        assertEquals(parent.snapshotId, rolledBack.snapshotId)
        assertEquals(ActivationCandidateState.ROLLED_BACK, storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
    }

    @Test
    fun changedOcrPublicationStaysShadowedUntilCandidatePointerCommit() = runBlocking {
        val assetId = insertAsset(111)
        val inserted = checkNotNull(storage.catalogDao.asset(assetId))
        assertEquals(1, storage.catalogDao.updateAsset(inserted.copy(sourceFingerprint = OcrSourceFingerprint)))
        val parentOcrEpoch = publishOcr(assetId, "ocr-parent", ComponentHash, "parent invoice")
        val visualLease =
            stageRunningWork(
                assetId,
                "visual-parent-ocr-cutover",
                sourceFingerprint = OcrSourceFingerprint,
            )
        val parentRequest =
            request(111, assetId, visualLease).let { base ->
                base.copy(
                    lexicalPublicationEpoch = parentOcrEpoch,
                    records = base.records.map { it.copy(sourceFingerprint = OcrSourceFingerprint) },
                )
            }
        val parent =
            store().publish(parentRequest)
        now += 100
        val changedPack =
            pack().copy(
                packVersion = "0.1.0-alpha.2",
                manifestSha256 = ShadowComponentHash,
                relativeDirectory = "model-packs/test-ocr-shadow",
                installedAtMillis = now,
            )
        storage.modelPackDao.registerInstalledCandidate(changedPack)
        val candidateSnapshotId = "candidate-snapshot-ocr"
        val targetChannels =
            storage.vectorIndexDao.snapshotChannels(parent.snapshotId).map { channel ->
                if (channel.channel == IndexChannel.OCR) {
                    channel.copy(
                        snapshotId = candidateSnapshotId,
                        componentHash = ShadowComponentHash,
                        inheritedFromSnapshotId = null,
                    )
                } else {
                    channel.copy(
                        snapshotId = candidateSnapshotId,
                        inheritedFromSnapshotId = parent.snapshotId,
                    )
                }
            }
        val activator = activator()
        val candidate =
            activator.register(
                candidateId = "candidate-ocr",
                snapshotId = candidateSnapshotId,
                pack = changedPack,
                targetChannels = targetChannels,
            )
        assertEquals(
            ActivationCandidateChannelAction.REBUILD_SHADOW,
            storage.vectorIndexDao.activationCandidateChannels(candidate.candidateId)
                .single { it.channel == IndexChannel.OCR }.action,
        )

        val candidateOcrEpoch = publishOcr(assetId, "ocr-candidate", ShadowComponentHash, "candidate receipt")
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(
            listOf(assetId),
            storage.ocrDao.candidateSnapshotAt(
                lexicalMatchQuery = "parent",
                trigramMatchQuery = null,
                pipelineVersion = "ocr-v1",
                componentHash = ComponentHash,
                maximumPublicationEpoch = parentOcrEpoch,
                limit = 10,
            ).documents.map { it.assetId },
        )

        val child =
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                packVersion = changedPack.packVersion,
                packManifestSha256 = changedPack.manifestSha256,
                lexicalPublicationEpoch = candidateOcrEpoch,
                capturedAccessRevision = candidate.capturedAccessRevision,
                catalogWatermark = candidate.capturedCatalogWatermark,
                createdAtMillis = now,
            )
        activator.markReady(candidate.candidateId, child, targetChannels)
        activator.activate(candidate.candidateId)

        assertEquals(child.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(
            listOf(assetId),
            storage.ocrDao.candidateSnapshotAt(
                lexicalMatchQuery = "candidate",
                trigramMatchQuery = null,
                pipelineVersion = "ocr-v1",
                componentHash = ShadowComponentHash,
                maximumPublicationEpoch = child.lexicalPublicationEpoch,
                limit = 10,
            ).documents.map { it.assetId },
        )
    }

    @Test
    fun accessChangeRejectsReadyCandidateWithoutMovingActivePointer() = runBlocking {
        val assetId = insertAsset(1)
        val parent = store().publish(request(102, assetId, stageRunningWork(assetId, "activation-race")))
        now += 100
        val activator = activator()
        val candidate = activator.register("candidate-102", "candidate-snapshot-102", pack())
        activator.markReady(
            candidate.candidateId,
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                capturedAccessRevision = AccessRevision,
                catalogWatermark = candidate.capturedCatalogWatermark,
                createdAtMillis = now,
            ),
        )
        storage.catalogDao.recordAccessObservation("Full", AccessRevision + 1, now + 1)

        val failure = runCatching { activator.activate(candidate.candidateId) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(
            ActivationCandidateState.READY_TO_ACTIVATE,
            storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state,
        )
    }

    @Test
    fun catalogDeltaAfterCapturedCutoverRequiresReconciliationBeforeActivation() = runBlocking {
        storage.catalogDao.replaceWatermark(CatalogWatermarkEntity(catalogRevision = 1, lastSuccessfulInventoryRunId = null, updatedAtMillis = now))
        val assetId = insertAsset(1)
        val parent = store().publish(request(105, assetId, stageRunningWork(assetId, "activation-cutover")))
        now += 100
        val activator = activator()
        val candidate = activator.register("candidate-105", "candidate-snapshot-105", pack())
        activator.markReady(
            candidate.candidateId,
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                capturedAccessRevision = AccessRevision,
                catalogWatermark = candidate.capturedCatalogWatermark,
                createdAtMillis = now,
            ),
        )
        storage.catalogDao.replaceWatermark(
            CatalogWatermarkEntity(catalogRevision = 2, lastSuccessfulInventoryRunId = null, updatedAtMillis = now + 1),
        )

        val failure = runCatching { activator.activate(candidate.candidateId) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(ActivationCandidateState.READY_TO_ACTIVATE, storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state)
    }

    @Test
    fun activationFailpointsExposeEitherParentOrFullyCommittedChildAfterRestart() = runBlocking {
        val assetId = insertAsset(1)
        val parent = store().publish(request(103, assetId, stageRunningWork(assetId, "activation-failpoint")))
        now += 100
        val candidate = activator().register("candidate-103", "candidate-snapshot-103", pack())
        val child =
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                capturedAccessRevision = AccessRevision,
                catalogWatermark = candidate.capturedCatalogWatermark,
                createdAtMillis = now,
            )
        activator().markReady(candidate.candidateId, child)
        val beforeCommit =
            AtomicSnapshotActivator(
                vectors = storage.vectorIndexDao,
                verifier = ActivationCandidateVerifier { _, _ -> },
                clock = { now },
                boundaryObserver = { boundary ->
                    if (boundary == ActivationBoundary.BEFORE_POINTER_COMMIT) throw SimulatedActivationDeath(boundary)
                },
            )

        assertTrue(runCatching { beforeCommit.activate(candidate.candidateId) }.exceptionOrNull() is SimulatedActivationDeath)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(ActivationCandidateState.READY_TO_ACTIVATE, storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state)

        val afterCommit =
            AtomicSnapshotActivator(
                vectors = storage.vectorIndexDao,
                verifier = ActivationCandidateVerifier { _, _ -> },
                clock = { now },
                boundaryObserver = { boundary ->
                    if (boundary == ActivationBoundary.AFTER_POINTER_COMMIT) throw SimulatedActivationDeath(boundary)
                },
            )
        assertTrue(runCatching { afterCommit.activate(candidate.candidateId) }.exceptionOrNull() is SimulatedActivationDeath)
        reopenStorage()
        assertEquals(child.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(ActivationCandidateState.ACTIVE, storage.vectorIndexDao.activationCandidate(candidate.candidateId)?.state)

        val recovery =
            VectorIndexRecovery(root, storage.vectorIndexDao).recover(
                nowMillis = now + 1,
                orphanGraceMillis = 0,
                deepVerifySegments = true,
            )
        assertEquals(child.snapshotId, recovery.activeAfter)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activePointer()?.rollbackSnapshotId)
    }

    @Test
    fun futureSnapshotFormatAndChangedPackCannotReuseIncompatibleGeneration() = runBlocking {
        val assetId = insertAsset(1)
        val parent = store().publish(request(104, assetId, stageRunningWork(assetId, "activation-contract")))
        now += 100
        val future = activator().register("candidate-future", "candidate-snapshot-future", pack())
        val futureFailure =
            runCatching {
                activator().markReady(
                    future.candidateId,
                    parent.copy(
                        snapshotId = future.snapshotId,
                        parentSnapshotId = parent.snapshotId,
                        capturedAccessRevision = AccessRevision,
                        catalogWatermark = future.capturedCatalogWatermark,
                        createdAtMillis = now,
                        formatVersion = 2,
                    ),
                )
            }.exceptionOrNull()
        assertTrue(futureFailure is IllegalStateException)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())

        assertTrue(activator().reject(future.candidateId, "UNSUPPORTED_SNAPSHOT_FORMAT"))
        val changedPack =
            pack().copy(
                packVersion = "0.1.0-alpha.2",
                manifestSha256 = "e".repeat(64),
                relativeDirectory = "model-packs/test-v2",
                installedAtMillis = now,
            )
        storage.modelPackDao.registerInstalledCandidate(changedPack)
        val changedSnapshotId = "candidate-snapshot-changed"
        val targetChannels =
            storage.vectorIndexDao.snapshotChannels(parent.snapshotId).map { component ->
                component.copy(
                    snapshotId = changedSnapshotId,
                    componentHash =
                        if (component.channel == IndexChannel.VISUAL) changedPack.manifestSha256 else component.componentHash,
                    inheritedFromSnapshotId = null,
                )
            }
        val changed =
            activator().register(
                "candidate-changed",
                changedSnapshotId,
                changedPack,
                targetChannels,
            )
        val changedSnapshot =
            parent.copy(
                snapshotId = changed.snapshotId,
                parentSnapshotId = parent.snapshotId,
                packVersion = changedPack.packVersion,
                packManifestSha256 = changedPack.manifestSha256,
                capturedAccessRevision = AccessRevision,
                catalogWatermark = changed.capturedCatalogWatermark,
                createdAtMillis = now,
            )
        val implicitInheritanceFailure =
            runCatching { activator().markReady(changed.candidateId, changedSnapshot) }.exceptionOrNull()
        assertTrue(implicitInheritanceFailure is IllegalStateException)
        val incompatibleChannels = targetChannels
        val inheritedGenerationFailure =
            runCatching {
                activator().markReady(
                    changed.candidateId,
                    changedSnapshot,
                    incompatibleChannels,
                )
            }.exceptionOrNull()
        assertTrue(inheritedGenerationFailure is IllegalStateException)
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
    }

    @Test
    fun changedVisualChannelBuildsInShadowWithoutInterruptingParentSearch() = runBlocking {
        val assetId = insertAsset(1)
        val parent = store().publish(request(106, assetId, stageRunningWork(assetId, "shadow-parent")))
        val parentManifestRevision = checkNotNull(parent.visualManifestRevision)
        assertEquals(
            1,
            storage.vectorIndexDao.currentVisualEvidence(
                parentManifestRevision,
                listOf(assetId),
                "visual-v1",
                ComponentHash,
            ).size,
        )

        now += 100
        val changedPack =
            pack().copy(
                packVersion = "0.1.0-alpha.2",
                manifestSha256 = ShadowComponentHash,
                relativeDirectory = "model-packs/test-shadow",
                installedAtMillis = now,
            )
        storage.modelPackDao.registerInstalledCandidate(changedPack)
        val shadowGeneration =
            generation().copy(
                generationId = "visual-generation-shadow",
                packVersion = changedPack.packVersion,
                componentHash = ShadowComponentHash,
                embeddingSpaceHash = ShadowEmbeddingHash,
                createdAtMillis = now,
            )
        storage.vectorIndexDao.createGeneration(shadowGeneration)
        val candidateSnapshotId = "candidate-snapshot-shadow"
        val targetChannels =
            storage.vectorIndexDao.snapshotChannels(parent.snapshotId).map { channel ->
                if (channel.channel == IndexChannel.VISUAL) {
                    channel.copy(
                        snapshotId = candidateSnapshotId,
                        componentHash = ShadowComponentHash,
                        embeddingSpaceHash = ShadowEmbeddingHash,
                        inheritedFromSnapshotId = null,
                    )
                } else {
                    channel.copy(
                        snapshotId = candidateSnapshotId,
                        inheritedFromSnapshotId = parent.snapshotId,
                    )
                }
            }
        val candidate =
            activator().register(
                candidateId = "candidate-shadow",
                snapshotId = candidateSnapshotId,
                pack = changedPack,
                targetChannels = targetChannels,
            )
        val plan = storage.vectorIndexDao.activationCandidateChannels(candidate.candidateId)
        assertEquals(
            ActivationCandidateChannelAction.REBUILD_SHADOW,
            plan.single { it.channel == IndexChannel.VISUAL }.action,
        )

        val deltaAssetId = insertAsset(2)
        storage.catalogDao.replaceWatermark(
            CatalogWatermarkEntity(
                catalogRevision = 1,
                lastSuccessfulInventoryRunId = null,
                updatedAtMillis = now,
            ),
        )

        val shadowLease =
            stageRunningWork(
                assetId = assetId,
                leaseToken = "shadow-visual-work",
                componentHash = ShadowComponentHash,
            )
        assertEquals(
            1,
            storage.vectorIndexDao.currentVisualEvidence(
                parentManifestRevision,
                listOf(assetId),
                "visual-v1",
                ComponentHash,
            ).size,
        )
        val firstShadowManifest =
            store().publishShadow(
                request(107, assetId, shadowLease).copy(
                    publicationToken = "publication-shadow",
                    generationId = shadowGeneration.generationId,
                    manifestRevision = "manifest-shadow",
                    snapshotId = candidate.snapshotId,
                    records =
                        listOf(
                            PublishedVectorRecord(
                                recordId = assetId,
                                assetId = assetId,
                                chunkOrdinal = 0,
                                sourceFingerprint = "source-$assetId",
                                accessRevision = AccessRevision,
                                vector = ByteArray(Dimension) { 99.toByte() },
                            ),
                        ),
                ),
                parentManifestRevision = null,
            )
        val deltaShadowLease =
            stageRunningWork(
                assetId = deltaAssetId,
                leaseToken = "shadow-visual-delta",
                componentHash = ShadowComponentHash,
            )
        val shadowManifest =
            store().publishShadow(
                request(108, deltaAssetId, deltaShadowLease).copy(
                    publicationToken = "publication-shadow-delta",
                    generationId = shadowGeneration.generationId,
                    manifestRevision = "manifest-shadow-delta",
                    snapshotId = candidate.snapshotId,
                    records =
                        listOf(
                            PublishedVectorRecord(
                                recordId = deltaAssetId,
                                assetId = deltaAssetId,
                                chunkOrdinal = 0,
                                sourceFingerprint = "source-$deltaAssetId",
                                accessRevision = AccessRevision,
                                vector = ByteArray(Dimension) { 55.toByte() },
                            ),
                        ),
                ),
                parentManifestRevision = firstShadowManifest.revision,
            )
        val reconciled =
            activator().reconcileCatalogWatermark(
                candidateId = candidate.candidateId,
                expectedWatermark = candidate.capturedCatalogWatermark,
                nextWatermark = 1,
            )
        assertEquals(parent.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(
            1,
            storage.vectorIndexDao.currentVisualEvidence(
                parentManifestRevision,
                listOf(assetId),
                "visual-v1",
                ComponentHash,
            ).size,
        )

        val preparedChannels =
            targetChannels.map { channel ->
                if (channel.channel == IndexChannel.VISUAL) {
                    channel.copy(
                        generationId = shadowGeneration.generationId,
                        manifestRevision = shadowManifest.revision,
                        inheritedFromSnapshotId = null,
                    )
                } else {
                    channel
                }
            }
        val child =
            parent.copy(
                snapshotId = candidate.snapshotId,
                parentSnapshotId = parent.snapshotId,
                packVersion = changedPack.packVersion,
                packManifestSha256 = changedPack.manifestSha256,
                visualManifestRevision = shadowManifest.revision,
                catalogWatermark = reconciled.capturedCatalogWatermark,
                capturedAccessRevision = candidate.capturedAccessRevision,
                createdAtMillis = now,
            )
        activator().markReady(candidate.candidateId, child, preparedChannels)
        activator().activate(candidate.candidateId)

        assertEquals(child.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(
            2,
            storage.vectorIndexDao.currentVisualEvidence(
                shadowManifest.revision,
                listOf(assetId, deltaAssetId),
                "visual-v1",
                ShadowComponentHash,
            ).size,
        )
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
    fun perceptualHashSearchUsesActiveSnapshotEpochAndReleasesLease() = runBlocking {
        val sourceAsset = insertAsset(1)
        val similarAsset = insertAsset(2)
        suspend fun publishHash(assetId: Long, bits: Long, suffix: String) {
            val lease = "phash-$suffix"
            storage.indexStateDao.replaceWork(
                IndexChannelWorkEntity(
                    assetId = assetId,
                    channel = IndexChannel.PHASH,
                    state = IndexWorkState.RUNNING,
                    sourceFingerprint = "source-$assetId",
                    accessRevision = AccessRevision,
                    pipelineVersion = app.nayti.search.engine.similarity.PerceptualHashV1.PipelineVersion,
                    componentHash = app.nayti.search.engine.similarity.PerceptualHashV1.ComponentHash,
                    attempt = 1,
                    leaseToken = lease,
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
            val draft =
                PerceptualHashDraft(
                    assetId = assetId,
                    sourceFingerprint = "source-$assetId",
                    accessRevision = AccessRevision,
                    pipelineVersion = app.nayti.search.engine.similarity.PerceptualHashV1.PipelineVersion,
                    componentHash = app.nayti.search.engine.similarity.PerceptualHashV1.ComponentHash,
                    hashBits = bits,
                )
            checkNotNull(
                storage.perceptualHashDao.commit(
                    leaseToken = lease,
                    publicationToken = "phash-publication-$suffix",
                    draft = draft,
                    expectedIdentity = PerceptualHashCodec.identity(draft),
                    nowMillis = now,
                ),
            )
        }
        publishHash(sourceAsset, 0x1234, "source")
        publishHash(similarAsset, 0x1235, "similar")
        val active =
            store().publish(
                request(2_051, sourceAsset, stageRunningWork(sourceAsset, "phash-active"))
                    .copy(pHashPublicationEpoch = 2),
            )
        val search =
            PerceptualHashSearch(
                hashes = storage.perceptualHashDao,
                vectors = storage.vectorIndexDao,
                clock = { now },
                leaseTokens = { "phash-query-test" },
            )

        val result = search.nearDuplicates(sourceAsset)

        assertEquals(PerceptualHashSearchStatus.READY, result.status)
        assertEquals(active.snapshotId, result.snapshotId)
        assertEquals(AccessRevision, result.accessRevision)
        assertEquals(listOf(similarAsset), result.hits.map { it.assetId })
        assertNull(storage.vectorIndexDao.queryLease("phash-query-test"))

        storage.catalogDao.recordAccessObservation("Full", AccessRevision + 1, now + 1)
        assertEquals(PerceptualHashSearchStatus.SOURCE_NOT_INDEXED, search.nearDuplicates(sourceAsset).status)
        assertNull(storage.vectorIndexDao.queryLease("phash-query-test"))
    }

    @Test
    fun textToImageSearchUsesActiveEmbeddingContractAndReleasesResources() = runBlocking {
        val firstAsset = insertAsset(1)
        store().publish(
            request(2_101, firstAsset, stageRunningWork(firstAsset, "text-first")).withVector(100),
        )
        now += 100
        val secondAsset = insertAsset(2)
        store().publish(
            request(2_102, secondAsset, stageRunningWork(secondAsset, "text-second")).withVector(60),
        )
        now += 100
        val thirdAsset = insertAsset(3)
        store().publish(
            request(2_103, thirdAsset, stageRunningWork(thirdAsset, "text-third")).withVector(-100),
        )
        var openedContract: VisualQueryContract? = null
        var sessionClosed = false
        val similarity =
            VisualSimilaritySearch(
                vectors = storage.vectorIndexDao,
                vectorRoot = root,
                clock = { now },
                leaseTokens = { "text-query-test" },
            )
        val search =
            VisualTextSearch(
                similarity = similarity,
                sessions = VisualTextQuerySessionFactory { contract ->
                    openedContract = contract
                    object : VisualTextQuerySession {
                        override val embeddingSpaceHash = contract.embeddingSpaceHash
                        override val dimension = contract.dimension

                        override fun encodeQuery(text: String): ByteArray {
                            assertEquals("красный автомобиль", text)
                            return ByteArray(contract.dimension) { 100.toByte() }
                        }

                        override fun close() {
                            sessionClosed = true
                        }
                    }
                },
            )

        val result = search.search("  красный автомобиль  ", limit = 2)

        assertEquals(VisualTextSearchStatus.READY, result.status)
        assertEquals(listOf(firstAsset, secondAsset), result.hits.map { it.assetId })
        assertEquals(EmbeddingHash, openedContract?.embeddingSpaceHash)
        assertEquals(PackManifestHash, openedContract?.packManifestSha256)
        assertTrue(sessionClosed)
        assertNull(storage.vectorIndexDao.queryLease("text-query-test"))
    }

    @Test
    fun textToImageSearchRejectsIncompatibleSessionAndStillReleasesLease() = runBlocking {
        val assetId = insertAsset(1)
        store().publish(
            request(2_201, assetId, stageRunningWork(assetId, "text-contract")).withVector(100),
        )
        var sessionClosed = false
        val search =
            VisualTextSearch(
                similarity =
                    VisualSimilaritySearch(
                        vectors = storage.vectorIndexDao,
                        vectorRoot = root,
                        clock = { now },
                        leaseTokens = { "text-invalid-session" },
                    ),
                sessions = VisualTextQuerySessionFactory { contract ->
                    object : VisualTextQuerySession {
                        override val embeddingSpaceHash = SecondEmbeddingHash
                        override val dimension = contract.dimension
                        override fun encodeQuery(text: String) = ByteArray(contract.dimension)
                        override fun close() {
                            sessionClosed = true
                        }
                    }
                },
            )

        val failure = runCatching { search.search("test") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(sessionClosed)
        assertNull(storage.vectorIndexDao.queryLease("text-invalid-session"))
    }

    @Test
    fun unifiedSearchRoutesVisualScenesButNeverSendsIdentifiersToSiglip() = runBlocking {
        val firstAsset = insertAsset(1)
        store().publish(
            request(2_301, firstAsset, stageRunningWork(firstAsset, "unified-first"))
                .withVector(100)
                .copy(lexicalPublicationEpoch = 0),
        )
        now += 100
        val secondAsset = insertAsset(2)
        store().publish(
            request(2_302, secondAsset, stageRunningWork(secondAsset, "unified-second"))
                .withVector(-100)
                .copy(lexicalPublicationEpoch = 0),
        )
        var visualSessionsOpened = 0
        val similarity =
            VisualSimilaritySearch(
                vectors = storage.vectorIndexDao,
                vectorRoot = root,
                clock = { now },
                leaseTokens = { "unified-visual-query" },
            )
        val visual =
            VisualTextSearch(
                similarity = similarity,
                sessions = VisualTextQuerySessionFactory { contract ->
                    visualSessionsOpened += 1
                    object : VisualTextQuerySession {
                        override val embeddingSpaceHash = contract.embeddingSpaceHash
                        override val dimension = contract.dimension
                        override fun encodeQuery(text: String) = ByteArray(contract.dimension) { 100.toByte() }
                        override fun close() = Unit
                    }
                },
            )
        val semantic =
            OcrSemanticSearch(
                vectors = storage.vectorIndexDao,
                semantic = storage.ocrSemanticDao,
                vectorRoot = root,
                sessions = SemanticQuerySessionFactory { error("Semantic manifest is absent") },
                clock = { now },
                leaseTokens = { "unified-semantic-query" },
            )
        val unified =
            UnifiedSearch(
                vectors = storage.vectorIndexDao,
                text = OcrHybridSearch(storage.ocrDao, storage.vectorIndexDao, semantic),
                visual = visual,
                clock = { now },
                leaseTokens = { "unified-query-session" },
            )

        val scene =
            unified.search(
                query = "red car on a road",
                pipelineVersion = "visual-v1",
                fallbackComponentHash = ComponentHash,
            )

        assertEquals(MultimodalQueryIntent.VISUAL_SCENE, scene.intent)
        assertEquals(listOf(firstAsset, secondAsset), scene.hits.map { it.assetId })
        assertEquals(UnifiedSearchReason.VISUAL_CONTENT, scene.hits.first().reason)
        assertEquals(1, visualSessionsOpened)
        assertEquals(scene.snapshotId, storage.vectorIndexDao.activeSnapshotId())
        assertEquals(AccessRevision, scene.accessRevision)
        assertNull(storage.vectorIndexDao.queryLease("unified-query-session"))
        assertNull(storage.vectorIndexDao.queryLease("unified-semantic-query"))
        assertNull(storage.vectorIndexDao.queryLease("unified-visual-query"))

        val identifier =
            unified.search(
                query = "№ АБ-123/45",
                pipelineVersion = "visual-v1",
                fallbackComponentHash = ComponentHash,
            )

        assertEquals(MultimodalQueryIntent.IDENTIFIER, identifier.intent)
        assertTrue(identifier.hits.isEmpty())
        assertEquals(1, visualSessionsOpened)
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

    private fun activator() =
        AtomicSnapshotActivator(
            vectors = storage.vectorIndexDao,
            verifier = ActivationCandidateVerifier { snapshot, channels ->
                check(
                    VectorSnapshotIntegrityVerifier(root, storage.vectorIndexDao)
                        .verify(snapshot, deepVerifySegments = true, candidateChannels = channels),
                )
            },
            clock = { now },
        )

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
                    accessRevision = AccessRevision,
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

    private suspend fun stageRunningWork(
        assetId: Long,
        leaseToken: String,
        componentHash: String = ComponentHash,
        sourceFingerprint: String = "source-$assetId",
    ): String {
        storage.indexStateDao.replaceWork(
            IndexChannelWorkEntity(
                assetId = assetId,
                channel = IndexChannel.VISUAL,
                state = IndexWorkState.RUNNING,
                sourceFingerprint = sourceFingerprint,
                accessRevision = AccessRevision,
                pipelineVersion = "visual-v1",
                componentHash = componentHash,
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

    private suspend fun publishOcr(
        assetId: Long,
        publicationToken: String,
        componentHash: String,
        text: String,
    ): Long {
        val leaseToken = "lease-$publicationToken"
        val sourceFingerprint = checkNotNull(storage.catalogDao.asset(assetId)).sourceFingerprint
        storage.indexStateDao.replaceWork(
            IndexChannelWorkEntity(
                assetId = assetId,
                channel = IndexChannel.OCR,
                state = IndexWorkState.RUNNING,
                sourceFingerprint = sourceFingerprint,
                accessRevision = AccessRevision,
                pipelineVersion = "ocr-v1",
                componentHash = componentHash,
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
        val document =
            OcrDocumentDraft(
                assetId = assetId,
                sourceFingerprint = sourceFingerprint,
                accessRevision = AccessRevision,
                pipelineVersion = "ocr-v1",
                componentHash = componentHash,
                sourceWidth = 10,
                sourceHeight = 10,
                rawText = text,
                displayText = text,
                canonicalText = text,
                stemText = text,
                identifierText = "",
                normalizerVersion = "normalizer-v1",
                stemmerVersion = "stemmer-v1",
                identifierVersion = "identifier-v1",
            )
        val regions =
            listOf(
                OcrRegionDraft(
                    rawText = text,
                    displayText = text,
                    canonicalText = text,
                    confidenceMicros = 900_000,
                    x0Micros = 0,
                    y0Micros = 0,
                    x1Micros = 1_000_000,
                    y1Micros = 0,
                    x2Micros = 1_000_000,
                    y2Micros = 1_000_000,
                    x3Micros = 0,
                    y3Micros = 1_000_000,
                ),
            )
        return checkNotNull(
            storage.ocrDao.commitOcrPublication(
                leaseToken = leaseToken,
                publicationToken = publicationToken,
                expectedIdentity = OcrPublicationCodec.identity(document, regions),
                document = document,
                regions = regions,
                nowMillis = now,
            ),
        ).publicationEpoch
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
    private class SimulatedActivationDeath(val boundary: ActivationBoundary) : RuntimeException()
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
        const val ShadowComponentHash = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val ShadowEmbeddingHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val OcrSourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CrashIterations = 100
        val Boundaries = VectorPublicationBoundary.entries
    }
}
