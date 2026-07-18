package app.nayti.storage

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrPublicationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase
    private lateinit var catalog: CatalogDao
    private lateinit var index: IndexStateDao
    private lateinit var ocr: OcrDao
    private lateinit var semantic: OcrSemanticDao
    private lateinit var quarantine: QuarantineDao

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun publicationAtomicallyPersistsDocumentRegionsAndSearchEvidence() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val claim = claimOcr(assetId)
        val document = document(assetId, "Счёт за кофе ABC-123", "счет за кофе abc 123", "счет кофе", "ABC 123")
        val regions = listOf(region("Счёт за кофе", "счет за кофе"), region("ABC-123", "abc 123"))
        val identity = OcrPublicationCodec.identity(document, regions)

        val publication =
            ocr.commitOcrPublication(
                leaseToken = checkNotNull(claim.leaseToken),
                publicationToken = "ocr-publication-a",
                expectedIdentity = identity,
                document = document,
                regions = regions,
                nowMillis = 20,
            )

        assertEquals(1L, publication?.publicationEpoch)
        assertEquals(IndexWorkState.DONE, index.work(assetId, IndexChannel.OCR)?.state)
        assertEquals(identity.sha256, index.publication(assetId, IndexChannel.OCR)?.resultSha256)
        assertEquals(2, ocr.document(assetId)?.regionCount)
        assertEquals(regions.map(OcrRegionDraft::canonicalText), ocr.regions(assetId).map(OcrRegionEntity::canonicalText))
        assertEquals(
            listOf(assetId),
            ocr.lexicalCandidates(
                "canonical : \"счет за кофе\"",
                PipelineVersion,
                ComponentHash,
                maximumPublicationEpoch = 1,
                limit = 10,
            ).map(OcrLexicalCandidate::assetId),
        )
        assertTrue(
            ocr.lexicalCandidates(
                "кофе",
                PipelineVersion,
                ComponentHash,
                maximumPublicationEpoch = 0,
                limit = 10,
            ).isEmpty(),
        )
        assertEquals(
            listOf(assetId),
            ocr.trigramCandidates(
                "\"коф\"",
                PipelineVersion,
                ComponentHash,
                maximumPublicationEpoch = 1,
                limit = 10,
            ).map(OcrLexicalCandidate::assetId),
        )
        assertEquals(
            listOf(assetId),
            ocr.lexicalCandidates(
                "identifiers : \"ABC 123\"",
                PipelineVersion,
                ComponentHash,
                maximumPublicationEpoch = 1,
                limit = 10,
            ).map(OcrLexicalCandidate::assetId),
        )

        reopenDatabase()
        assertEquals(document.displayText, ocr.document(assetId)?.displayText)
        assertEquals(2, ocr.regions(assetId).size)
        assertEquals(1L, ocr.publicationClock()?.lastEpoch)
    }

    @Test
    fun quarantineFinalizationDeletesOcrFtsAndWorkAfterVectorReferencesAreGone() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val claim = claimOcr(assetId)
        val document = document(assetId, "Private receipt", "private receipt", "privat receipt", "")
        val regions = listOf(region("Private receipt", "private receipt"))
        checkNotNull(
            ocr.commitOcrPublication(
                checkNotNull(claim.leaseToken),
                "ocr-private",
                OcrPublicationCodec.identity(document, regions),
                document,
                regions,
                20,
            ),
        )
        val asset = checkNotNull(catalog.asset(assetId))
        assertEquals(
            1,
            catalog.updateAsset(
                asset.copy(
                    availability = CatalogAvailability.OUT_OF_SCOPE,
                    quarantineStartedAtMillis = 50,
                ),
            ),
        )

        assertEquals(listOf(assetId), quarantine.expiredAssets(cutoffMillis = 100, limit = 10).map { it.assetId })
        assertEquals(1L, catalog.counts().retainedQuarantine)
        assertEquals(1, quarantine.finalizePurge(listOf(assetId), cutoffMillis = 100, nowMillis = 200))

        assertNull(ocr.document(assetId))
        assertTrue(ocr.regions(assetId).isEmpty())
        assertTrue(ocr.lexicalCandidates("private", PipelineVersion, ComponentHash, 1, 10).isEmpty())
        assertNull(index.work(assetId, IndexChannel.OCR))
        assertEquals(200L, catalog.asset(assetId)?.derivedDataPurgedAtMillis)
        assertEquals(0L, catalog.counts().retainedQuarantine)
    }

    @Test
    fun changedOcrContractPublishesBesidePinnedParentEpoch() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val parentClaim = claimOcr(assetId)
        val parent = document(assetId, "Parent invoice", "parent invoice", "parent invoic", "")
        val parentRegions = listOf(region("Parent invoice", "parent invoice"))
        checkNotNull(
            ocr.commitOcrPublication(
                checkNotNull(parentClaim.leaseToken),
                "ocr-parent",
                OcrPublicationCodec.identity(parent, parentRegions),
                parent,
                parentRegions,
                20,
            ),
        )

        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, PipelineVersion, NewComponentHash, 21)
        val candidateClaim = index.claimBatch(WindowId, IndexChannel.OCR, "claim-candidate", 22, 100, 1).single()
        val candidate =
            document(assetId, "Candidate receipt", "candidate receipt", "candidat receipt", "")
                .copy(componentHash = NewComponentHash)
        val candidateRegions = listOf(region("Candidate receipt", "candidate receipt"))
        checkNotNull(
            ocr.commitOcrPublication(
                checkNotNull(candidateClaim.leaseToken),
                "ocr-candidate",
                OcrPublicationCodec.identity(candidate, candidateRegions),
                candidate,
                candidateRegions,
                23,
            ),
        )

        assertEquals(
            listOf(assetId),
            ocr.lexicalCandidates("parent", PipelineVersion, ComponentHash, 1, 10).map { it.assetId },
        )
        assertTrue(ocr.lexicalCandidates("candidate", PipelineVersion, NewComponentHash, 1, 10).isEmpty())
        assertEquals(
            listOf(assetId),
            ocr.lexicalCandidates("candidate", PipelineVersion, NewComponentHash, 2, 10).map { it.assetId },
        )
        assertEquals("Parent invoice", semantic.ocrDocument("ocr-parent")?.displayText)
        assertEquals("Candidate receipt", semantic.ocrDocument("ocr-candidate")?.displayText)
        assertEquals("Candidate receipt", ocr.document(assetId)?.displayText)
        assertEquals(2L, ocr.publicationClock()?.lastEpoch)
    }

    @Test
    fun garbageCollectionRemovesOnlySupersededUnrootedPublication() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val firstClaim = claimOcr(assetId)
        val first = document(assetId, "Old unrooted text", "old unrooted text", "old unroot text", "")
        val firstRegions = listOf(region("Old unrooted text", "old unrooted text"))
        checkNotNull(
            ocr.commitOcrPublication(
                checkNotNull(firstClaim.leaseToken),
                "ocr-gc-old",
                OcrPublicationCodec.identity(first, firstRegions),
                first,
                firstRegions,
                20,
            ),
        )
        val currentWork = checkNotNull(index.work(assetId, IndexChannel.OCR))
        index.replaceWork(
            currentWork.copy(
                state = IndexWorkState.RUNNING,
                attempt = currentWork.attempt + 1,
                leaseToken = "ocr-gc-new-lease",
                leaseExpiresAtMillis = 1_000,
                publicationToken = null,
                updatedAtMillis = 21,
            ),
        )
        val current = document(assetId, "Current protected text", "current protected text", "current protect text", "")
        val currentRegions = listOf(region("Current protected text", "current protected text"))
        checkNotNull(
            ocr.commitOcrPublication(
                "ocr-gc-new-lease",
                "ocr-gc-current",
                OcrPublicationCodec.identity(current, currentRegions),
                current,
                currentRegions,
                22,
            ),
        )

        assertEquals(1, ocr.collectGarbage())
        assertNull(ocr.documentByPublicationEpoch(1))
        assertTrue(ocr.regionsByPublicationEpoch(1).isEmpty())
        assertEquals("Current protected text", ocr.documentByPublicationEpoch(2)?.displayText)
        assertEquals(0, ocr.collectGarbage())
    }

    @Test
    fun staleSourceIsImmediatelyIneligibleAndRejectedReplacementPreservesOldEvidence() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val firstClaim = claimOcr(assetId)
        val firstDocument = document(assetId, "Старый договор", "старый договор", "стар договор", "")
        val firstRegions = listOf(region("Старый договор", "старый договор"))
        assertTrue(
            ocr.commitOcrPublication(
                checkNotNull(firstClaim.leaseToken),
                "ocr-old",
                OcrPublicationCodec.identity(firstDocument, firstRegions),
                firstDocument,
                firstRegions,
                20,
            ) != null,
        )

        val currentAsset = checkNotNull(catalog.asset(assetId))
        assertEquals(1, catalog.updateAsset(currentAsset.copy(sourceFingerprint = NewSourceFingerprint)))
        assertTrue(
            ocr.lexicalCandidates(
                "договор",
                PipelineVersion,
                ComponentHash,
                maximumPublicationEpoch = 1,
                limit = 10,
            ).isEmpty(),
        )

        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, PipelineVersion, ComponentHash, 21)
        val newClaim = index.claimBatch(WindowId, IndexChannel.OCR, "new-claim", 22, 100, 1).single()
        val staleReplacement = firstDocument.copy(sourceFingerprint = SourceFingerprint)
        assertNull(
            ocr.commitOcrPublication(
                checkNotNull(newClaim.leaseToken),
                "ocr-stale",
                OcrPublicationCodec.identity(staleReplacement, firstRegions),
                staleReplacement,
                firstRegions,
                23,
            ),
        )
        assertEquals("Старый договор", ocr.document(assetId)?.displayText)
        assertEquals(IndexWorkState.RUNNING, index.work(assetId, IndexChannel.OCR)?.state)

        val currentReplacement = firstDocument.copy(sourceFingerprint = NewSourceFingerprint)
        assertNull(
            ocr.commitOcrPublication(
                checkNotNull(newClaim.leaseToken),
                "ocr-old",
                OcrPublicationCodec.identity(currentReplacement, firstRegions),
                currentReplacement,
                firstRegions,
                24,
            ),
        )
        assertEquals("Старый договор", ocr.document(assetId)?.displayText)
    }

    @Test
    fun invalidIdentityCannotPartiallyPublishAndEmptyOcrIsAValidSuccess() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val claim = claimOcr(assetId)
        val empty = document(assetId, "", "", "", "")
        val actual = OcrPublicationCodec.identity(empty, emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ocr.commitOcrPublication(
                    checkNotNull(claim.leaseToken),
                    "ocr-invalid",
                    actual.copy(sha256 = InvalidHash),
                    empty,
                    emptyList(),
                    20,
                )
            }
        }
        assertNull(ocr.document(assetId))
        assertEquals(IndexWorkState.RUNNING, index.work(assetId, IndexChannel.OCR)?.state)

        assertTrue(
            ocr.commitOcrPublication(
                checkNotNull(claim.leaseToken),
                "ocr-empty",
                actual,
                empty,
                emptyList(),
                21,
            ) != null,
        )
        assertEquals(false, ocr.document(assetId)?.hasRecognizedText)
        assertEquals(0, ocr.document(assetId)?.regionCount)
        assertEquals(IndexWorkState.DONE, index.work(assetId, IndexChannel.OCR)?.state)
    }

    @Test
    fun semanticChunkSetIsImmutableIdempotentAndBoundToExactOcrPublication() = runBlocking {
        val assetId = insertAsset(SourceFingerprint)
        val claim = claimOcr(assetId)
        val document = document(assetId, "Quarterly product report\nRevenue increased", "", "", "")
        val regions = listOf(region("Quarterly product report", ""), region("Revenue increased", ""))
        val publicationToken = "ocr-semantic-source"
        checkNotNull(
            ocr.commitOcrPublication(
                checkNotNull(claim.leaseToken),
                publicationToken,
                OcrPublicationCodec.identity(document, regions),
                document,
                regions,
                20,
            ),
        )
        val materialization =
            OcrSemanticChunkCodec.materialize(
                OcrSemanticChunkSetDraft(
                    assetId = assetId,
                    sourceFingerprint = SourceFingerprint,
                    ocrPublicationToken = publicationToken,
                    chunkingVersion = "ocr-semantic-chunks-v1",
                    chunks =
                        listOf(
                            OcrSemanticChunkPayload(
                                ordinal = 0,
                                kind = "HEADER",
                                displayText = "Quarterly product report\nRevenue increased",
                                contentTokenCount = 5,
                                lineOrdinals = listOf(0, 1),
                                meanConfidenceMicros = 950_000,
                                reliableAlphabeticWordCount = 5,
                            ),
                        ),
                ),
                createdAtMillis = 21,
            )

        assertEquals(materialization.chunkSet, semantic.publishChunkSet(materialization))
        assertEquals(materialization.chunkSet, semantic.publishChunkSet(materialization))
        val retried =
            OcrSemanticChunkCodec.materialize(
                OcrSemanticChunkSetDraft(
                    assetId = assetId,
                    sourceFingerprint = SourceFingerprint,
                    ocrPublicationToken = publicationToken,
                    chunkingVersion = "ocr-semantic-chunks-v1",
                    chunks =
                        listOf(
                            OcrSemanticChunkPayload(
                                ordinal = 0,
                                kind = "HEADER",
                                displayText = "Quarterly product report\nRevenue increased",
                                contentTokenCount = 5,
                                lineOrdinals = listOf(0, 1),
                                meanConfidenceMicros = 950_000,
                                reliableAlphabeticWordCount = 5,
                            ),
                        ),
                ),
                createdAtMillis = 99,
            )
        assertEquals(materialization.chunkSet, semantic.publishChunkSet(retried))
        assertEquals(materialization.chunks.single(), semantic.chunk(materialization.chunks.single().chunkId))
        assertEquals(materialization.lines, semantic.chunkLines(materialization.chunks.single().chunkId))

        val stale =
            OcrSemanticChunkCodec.materialize(
                OcrSemanticChunkSetDraft(
                    assetId = assetId,
                    sourceFingerprint = NewSourceFingerprint,
                    ocrPublicationToken = publicationToken,
                    chunkingVersion = "ocr-semantic-chunks-v1",
                    chunks = emptyList(),
                ),
                createdAtMillis = 22,
            )
        assertNull(semantic.publishChunkSet(stale))

        reopenDatabase()
        assertEquals(materialization.chunkSet, semantic.chunkSet(materialization.chunkSet.chunkSetId))
    }

    private suspend fun claimOcr(assetId: Long): IndexChannelWorkEntity {
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        index.insertOperation(
            IndexOperationEntity(
                operationId = OperationId,
                profileId = "balanced-v1",
                targetPackId = "nayti-offline-search",
                targetPackVersion = "0.1.0-alpha.1",
                denominatorCatalogRevision = 1,
                denominatorAssetCount = 1,
                state = IndexOperationState.PLANNED,
                autoResume = true,
                createdAtMillis = 0,
                updatedAtMillis = 0,
                completedAtMillis = null,
            ),
        )
        index.startExecutionWindow(
            IndexExecutionWindowEntity(
                windowId = WindowId,
                operationId = OperationId,
                hostType = "TEST",
                leaseToken = "window-lease",
                state = IndexExecutionWindowState.RUNNING,
                startedAtMillis = 0,
                expiresAtMillis = 1_000,
                finishedAtMillis = null,
            ),
            0,
        )
        index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, PipelineVersion, ComponentHash, 2)
        return index.claimBatch(WindowId, IndexChannel.OCR, "claim-a", 10, 100, 1).single()
    }

    private fun document(
        assetId: Long,
        raw: String,
        canonical: String,
        stems: String,
        identifiers: String,
    ): OcrDocumentDraft =
        OcrDocumentDraft(
            assetId = assetId,
            sourceFingerprint = SourceFingerprint,
            accessRevision = AccessRevision,
            pipelineVersion = PipelineVersion,
            componentHash = ComponentHash,
            sourceWidth = 1_440,
            sourceHeight = 1_080,
            rawText = raw,
            displayText = raw,
            canonicalText = canonical,
            stemText = stems,
            identifierText = identifiers,
            normalizerVersion = "normalizer-v1",
            stemmerVersion = "stemmer-v1",
            identifierVersion = "identifier-v1",
        )

    private fun region(raw: String, canonical: String): OcrRegionDraft =
        OcrRegionDraft(
            rawText = raw,
            displayText = raw,
            canonicalText = canonical,
            confidenceMicros = 950_000,
            x0Micros = 100_000,
            y0Micros = 100_000,
            x1Micros = 900_000,
            y1Micros = 100_000,
            x2Micros = 900_000,
            y2Micros = 200_000,
            x3Micros = 100_000,
            y3Micros = 200_000,
        )

    private suspend fun insertAsset(sourceFingerprint: String): Long =
        catalog.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = 7,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 1_440,
                height = 1_080,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = 1,
                dateTakenMillis = null,
                dateModifiedSeconds = 1,
                displayName = "receipt.jpg",
                bucketId = null,
                bucketDisplayName = null,
                relativePath = null,
                sourceFingerprint = sourceFingerprint,
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = 1,
            ),
        )

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        catalog = database.catalogDao()
        index = database.indexStateDao()
        ocr = database.ocrDao()
        semantic = database.ocrSemanticDao()
        quarantine = database.quarantineDao()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private companion object {
        const val DatabaseName = "ocr-publication-instrumented.db"
        const val OperationId = "operation-ocr"
        const val WindowId = "window-ocr"
        const val AccessRevision = 7L
        const val PipelineVersion = "ocr-v1"
        const val SourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NewSourceFingerprint = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ComponentHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val NewComponentHash = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val InvalidHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
