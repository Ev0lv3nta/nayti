package app.nayti.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.search.engine.lexical.LexicalIntent
import app.nayti.search.engine.lexical.LexicalTextNormalizer
import app.nayti.storage.CatalogAccessObservationEntity
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexChannelWorkEntity
import app.nayti.storage.IndexExecutionWindowEntity
import app.nayti.storage.IndexExecutionWindowState
import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState
import app.nayti.storage.OcrDocumentDraft
import app.nayti.storage.OcrPublicationCodec
import app.nayti.storage.OcrRegionDraft
import app.nayti.storage.StorageContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrLexicalSearchInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var storage: CatalogStorage
    private val normalizer = LexicalTextNormalizer()

    @Before
    fun setUp() {
        context.deleteDatabase(StorageContract.DatabaseFileName)
        storage = CatalogStorage.open(context)
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(StorageContract.DatabaseFileName)
    }

    @Test
    fun exactIdentifierPersonAndFuzzySearchReturnBoundedProvenance() = runBlocking {
        val restaurantId = insertAsset(7, FingerprintA)
        val receiptId = insertAsset(8, FingerprintB)
        val contractId = insertAsset(9, FingerprintC)
        val claims = startAndClaim(listOf(restaurantId, receiptId, contractId))
        publish(claims.getValue(restaurantId), "Ресторан на Невском")
        publish(claims.getValue(receiptId), "Счёт за кофе ABC-123")
        publish(claims.getValue(contractId), "Договор Иван Иванов")

        val search = OcrLexicalSearch(storage.ocrDao)
        val phrase = search.search("\"счёт за кофе\"", PipelineVersion, ComponentHash)
        assertEquals(LexicalIntent.QUOTED_PHRASE, phrase.intent)
        assertEquals(listOf(receiptId), phrase.hits.map(OcrLexicalHit::assetId))
        assertEquals(LexicalEvidence.QUOTED_PHRASE, phrase.hits.single().evidence)
        assertEquals(listOf(0), phrase.hits.single().matchedRegionOrdinals)

        val identifier = search.search("АВС-123", PipelineVersion, ComponentHash)
        assertEquals(listOf(receiptId), identifier.hits.map(OcrLexicalHit::assetId))
        assertEquals(LexicalEvidence.EXACT_IDENTIFIER, identifier.hits.single().evidence)

        val fuzzy = search.search("рестаран", PipelineVersion, ComponentHash)
        assertEquals(listOf(restaurantId), fuzzy.hits.map(OcrLexicalHit::assetId))
        assertEquals(LexicalEvidence.FUZZY_TEXT, fuzzy.hits.single().evidence)
        assertTrue(fuzzy.hits.single().displaySnippet.contains("Ресторан"))

        val person = search.search("Иван Иванов", PipelineVersion, ComponentHash)
        assertEquals(listOf(contractId), person.hits.map(OcrLexicalHit::assetId))
        assertEquals(LexicalEvidence.PERSON_NAME, person.hits.single().evidence)

        storage.catalogDao.replaceAccessObservation(
            CatalogAccessObservationEntity(
                accessScope = "Full",
                processAccessRevision = AccessRevision + 1,
                observationSequence = 2,
                observedAtMillis = 30,
            ),
        )
        assertTrue(search.search("договор", PipelineVersion, ComponentHash).hits.isEmpty())
    }

    private suspend fun startAndClaim(assetIds: List<Long>): Map<Long, IndexChannelWorkEntity> {
        val catalog = storage.catalogDao
        val index = storage.indexStateDao
        catalog.recordAccessObservation("Full", AccessRevision, 1)
        index.insertOperation(
            IndexOperationEntity(
                operationId = OperationId,
                profileId = "balanced-v1",
                targetPackId = "nayti-offline-search",
                targetPackVersion = "0.1.0-alpha.1",
                denominatorCatalogRevision = 1,
                denominatorAssetCount = assetIds.size.toLong(),
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
        assetIds.forEach { assetId ->
            index.ensureWork(assetId, IndexChannel.OCR, AccessRevision, PipelineVersion, ComponentHash, 2)
        }
        return index.claimBatch(WindowId, IndexChannel.OCR, "claim", 10, 100, assetIds.size).associateBy { it.assetId }
    }

    private suspend fun publish(claim: IndexChannelWorkEntity, text: String) {
        val forms = normalizer.normalize(listOf(text))
        val document =
            OcrDocumentDraft(
                assetId = claim.assetId,
                sourceFingerprint = claim.sourceFingerprint,
                accessRevision = claim.accessRevision,
                pipelineVersion = claim.pipelineVersion,
                componentHash = claim.componentHash,
                sourceWidth = 1_440,
                sourceHeight = 1_080,
                rawText = text,
                displayText = forms.display,
                canonicalText = forms.canonical,
                stemText = forms.stems,
                identifierText = forms.identifiers,
                normalizerVersion = LexicalTextNormalizer.NormalizerVersion,
                stemmerVersion = LexicalTextNormalizer.StemmerVersion,
                identifierVersion = LexicalTextNormalizer.IdentifierVersion,
            )
        val region =
            OcrRegionDraft(
                rawText = text,
                displayText = forms.display,
                canonicalText = forms.canonical,
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
        val identity = OcrPublicationCodec.identity(document, listOf(region))
        checkNotNull(
            storage.ocrDao.commitOcrPublication(
                leaseToken = checkNotNull(claim.leaseToken),
                publicationToken = "ocr-${claim.assetId}",
                expectedIdentity = identity,
                document = document,
                regions = listOf(region),
                nowMillis = 20,
            ),
        )
    }

    private suspend fun insertAsset(mediaStoreId: Long, fingerprint: String): Long =
        storage.catalogDao.insertAsset(
            CatalogAssetEntity(
                volumeName = "external_primary",
                mediaStoreId = mediaStoreId,
                mimeType = "image/jpeg",
                sizeBytes = 100,
                width = 1_440,
                height = 1_080,
                orientationDegrees = 0,
                generationAdded = 1,
                generationModified = 1,
                dateTakenMillis = null,
                dateModifiedSeconds = 1,
                displayName = "image-$mediaStoreId.jpg",
                bucketId = null,
                bucketDisplayName = null,
                relativePath = null,
                sourceFingerprint = fingerprint,
                availability = CatalogAvailability.AVAILABLE,
                lastSeenInventoryRunId = 1,
                missingFullObservationCount = 0,
                quarantineStartedAtMillis = null,
                sourceObservedAtMillis = 1,
            ),
        )

    private companion object {
        const val OperationId = "operation-search"
        const val WindowId = "window-search"
        const val AccessRevision = 7L
        const val PipelineVersion = "ocr-v1"
        const val FingerprintA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FingerprintB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val FingerprintC = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val ComponentHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
