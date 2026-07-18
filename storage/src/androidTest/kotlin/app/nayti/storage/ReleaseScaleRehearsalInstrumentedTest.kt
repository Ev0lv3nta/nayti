package app.nayti.storage

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded resource rehearsal for the release schema. Timings are diagnostics for regressions on
 * the emulator; device performance acceptance remains a separate Galaxy S23+ exercise.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseScaleRehearsalInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun productionSchemaStaysBoundedAndRevocationIsImmediateAtLibraryScale() = runBlocking {
        val insertStarted = SystemClock.elapsedRealtimeNanos()
        seedProductionCorpus()
        val insertMillis = elapsedMillis(insertStarted)
        assertEquals(SyntheticAssetCount, scalarLong("SELECT COUNT(*) FROM catalog_asset"))
        assertEquals(SyntheticAssetCount, scalarLong("SELECT COUNT(*) FROM ocr_document"))
        val lexicalMatches =
            scalarLong(
                "SELECT COUNT(*) FROM ocr_lexical_fts " +
                    "WHERE ocr_lexical_fts MATCH 'кофе AND чек'",
            )
        val eligibleMatches =
            scalarLong(
                "SELECT COUNT(*) FROM ocr_lexical_fts " +
                    "INNER JOIN ocr_document ON ocr_document.publicationEpoch = ocr_lexical_fts.rowid " +
                    "INNER JOIN catalog_asset ON catalog_asset.assetId = ocr_document.assetId " +
                    "INNER JOIN catalog_access_observation ON catalog_access_observation.singletonId = 1 " +
                    "WHERE ocr_lexical_fts MATCH 'кофе AND чек' " +
                    "AND catalog_asset.availability = 'AVAILABLE' " +
                    "AND catalog_asset.bucketId = $TargetBucketId " +
                    "AND catalog_asset.mimeType = 'image/jpeg' " +
                    "AND catalog_asset.sourceFingerprint = ocr_document.sourceFingerprint " +
                    "AND ocr_document.pipelineVersion = '$PipelineVersion' " +
                    "AND ocr_document.componentHash = '$ComponentHash' " +
                    "AND ocr_document.accessRevision = " +
                    "catalog_access_observation.processAccessRevision " +
                    "AND catalog_access_observation.accessScope != 'None'",
            )
        assertEquals(SyntheticAssetCount, lexicalMatches)
        assertTrue("Expected eligible production rows, found $eligibleMatches", eligibleMatches > 0)

        val warm = filteredSnapshot()
        assertSnapshotIsBoundedAndFiltered(warm)
        val queryStarted = SystemClock.elapsedRealtimeNanos()
        val measured = filteredSnapshot()
        val queryMillis = elapsedMillis(queryStarted)
        assertSnapshotIsBoundedAndFiltered(measured)

        withTimeout(ConcurrentExerciseTimeoutMillis) {
            coroutineScope {
                val readers = (1..4).map {
                    async(Dispatchers.Default) { repeat(3) { filteredSnapshot() } }
                }
                val revoke = async(Dispatchers.Default) {
                    database.catalogDao().recordAccessObservation(
                        accessScope = "None",
                        processAccessRevision = RevokedAccessRevision,
                        nowMillis = 30_000,
                    )
                }
                (readers + revoke).awaitAll()
            }
        }

        val afterRevocation = filteredSnapshot()
        assertTrue(afterRevocation.lexicalCandidates.isEmpty())
        assertTrue(afterRevocation.documents.isEmpty())
        assertTrue(afterRevocation.regions.isEmpty())

        database.close()
        database = openDatabase()
        assertEquals("wal", journalMode().lowercase(Locale.ROOT))
        assertEquals(SyntheticAssetCount, scalarLong("SELECT COUNT(*) FROM catalog_asset"))
        assertTrue(filteredSnapshot().documents.isEmpty())

        val databaseBytes = context.getDatabasePath(DatabaseName).length()
        val pssKilobytes = Debug.getPss()
        Log.i(
            LogTag,
            "assets=$SyntheticAssetCount insert_ms=$insertMillis query_ms=$queryMillis " +
                "db_bytes=$databaseBytes pss_kb=$pssKilobytes",
        )
        assertTrue("Database grew to $databaseBytes bytes", databaseBytes in 1..MaximumDatabaseBytes)
        assertTrue("Warm filtered query took ${queryMillis}ms", queryMillis < MaximumQueryMillis)
    }

    private suspend fun seedProductionCorpus() {
        database.useConnection(isReadOnly = false) { connection ->
            connection.withTransaction<Unit>(Transactor.SQLiteTransactionType.IMMEDIATE) {
                usePrepared(
                    "INSERT INTO catalog_access_observation(" +
                        "singletonId,accessScope,processAccessRevision,observationSequence,observedAtMillis" +
                        ") VALUES (1,'Full',$AccessRevision,1,10000)",
                ) { statement -> check(!statement.step()) }
                usePrepared(
                    "INSERT INTO index_publication_clock(singletonId,lastEpoch) " +
                        "VALUES (1,$SyntheticAssetCount)",
                ) { statement -> check(!statement.step()) }
                usePrepared(CatalogInsert) { catalog ->
                    for (assetId in 1L..SyntheticAssetCount) bindCatalog(catalog, assetId)
                }
                usePrepared(OcrDocumentInsert) { document ->
                    for (assetId in 1L..SyntheticAssetCount) bindDocument(document, assetId)
                }
                usePrepared(OcrRegionInsert) { region ->
                    for (assetId in 1L..SyntheticAssetCount) bindRegion(region, assetId)
                }
                usePrepared(LexicalInsert) { lexical ->
                    for (assetId in 1L..SyntheticAssetCount) bindLexical(lexical, assetId)
                }
                usePrepared(TrigramInsert) { trigram ->
                    for (assetId in 1L..SyntheticAssetCount) bindTrigram(trigram, assetId)
                }
                Unit
            }
        }
    }

    private fun bindCatalog(statement: androidx.sqlite.SQLiteStatement, assetId: Long) {
        statement.bindLong(1, assetId)
        statement.bindLong(2, assetId)
        statement.bindText(3, if (assetId % 2L == 0L) "image/jpeg" else "image/png")
        statement.bindLong(4, BaseTakenMillis + assetId * 1_000)
        statement.bindLong(5, if (assetId % AlbumModulo == 0L) TargetBucketId else OtherBucketId)
        statement.bindText(6, "$FingerprintPrefix$assetId")
        executeAndReset(statement)
    }

    private fun bindDocument(statement: androidx.sqlite.SQLiteStatement, assetId: Long) {
        val text = "Фото $assetId чек кофе магазин ${assetId % 97}"
        statement.bindLong(1, assetId)
        statement.bindText(2, "$FingerprintPrefix$assetId")
        statement.bindText(3, "ocr-$assetId")
        statement.bindLong(4, assetId)
        statement.bindText(5, text)
        statement.bindText(6, text)
        statement.bindText(7, text)
        statement.bindText(8, "фото чек кофе магазин")
        statement.bindText(9, "IMG-$assetId")
        executeAndReset(statement)
    }

    private fun bindRegion(statement: androidx.sqlite.SQLiteStatement, assetId: Long) {
        statement.bindLong(1, assetId)
        statement.bindLong(2, assetId)
        statement.bindText(3, "чек кофе")
        statement.bindText(4, "чек кофе")
        statement.bindText(5, "чек кофе")
        executeAndReset(statement)
    }

    private fun bindLexical(statement: androidx.sqlite.SQLiteStatement, assetId: Long) {
        statement.bindLong(1, assetId)
        statement.bindText(2, "Фото $assetId чек кофе магазин ${assetId % 97}")
        statement.bindText(3, "фото чек кофе магазин")
        statement.bindText(4, "IMG-$assetId")
        executeAndReset(statement)
    }

    private fun bindTrigram(statement: androidx.sqlite.SQLiteStatement, assetId: Long) {
        statement.bindLong(1, assetId)
        statement.bindText(2, "Фото $assetId чек кофе магазин ${assetId % 97}")
        executeAndReset(statement)
    }

    private fun executeAndReset(statement: androidx.sqlite.SQLiteStatement) {
        check(!statement.step())
        statement.reset()
        statement.clearBindings()
    }

    private suspend fun filteredSnapshot(): OcrCandidateSnapshot =
        database.ocrDao().candidateSnapshotAt(
            lexicalMatchQuery = "кофе AND чек",
            trigramMatchQuery = null,
            pipelineVersion = PipelineVersion,
            componentHash = ComponentHash,
            maximumPublicationEpoch = SyntheticAssetCount,
            limit = CandidateLimit,
            takenFromMillis = BaseTakenMillis + 1_000,
            takenBeforeMillis = BaseTakenMillis + (SyntheticAssetCount + 1) * 1_000,
            bucketId = TargetBucketId,
            mimeType = "image/jpeg",
        )

    private fun assertSnapshotIsBoundedAndFiltered(snapshot: OcrCandidateSnapshot) {
        assertTrue(snapshot.lexicalCandidates.isNotEmpty())
        assertTrue(snapshot.lexicalCandidates.size <= CandidateLimit)
        assertEquals(snapshot.lexicalCandidates.size, snapshot.documents.size)
        assertEquals(snapshot.documents.size, snapshot.regions.size)
        assertTrue(snapshot.documents.all { it.assetId % AlbumModulo == 0L && it.assetId % 2L == 0L })
    }

    private suspend fun scalarLong(sql: String): Long =
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(sql) { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

    private suspend fun journalMode(): String =
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("PRAGMA journal_mode") { statement ->
                check(statement.step())
                statement.getText(0)
            }
        }

    private fun openDatabase(): NaytiDatabase =
        Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setMultipleConnectionPool(maxNumOfReaders = 4, maxNumOfWriters = 1)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000

    private companion object {
        const val DatabaseName = "release-scale-rehearsal.db"
        const val LogTag = "NaytiScaleRehearsal"
        const val SyntheticAssetCount = 13_000L
        const val AccessRevision = 7L
        const val RevokedAccessRevision = 8L
        const val PipelineVersion = "ocr-scale-v1"
        const val ComponentHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FingerprintPrefix = "scale-fingerprint-"
        const val BaseTakenMillis = 1_700_000_000_000L
        const val TargetBucketId = 42L
        const val OtherBucketId = 7L
        const val AlbumModulo = 5L
        const val CandidateLimit = 100
        const val MaximumQueryMillis = 5_000L
        const val ConcurrentExerciseTimeoutMillis = 30_000L
        const val MaximumDatabaseBytes = 128L * 1_024 * 1_024

        val CatalogInsert =
            "INSERT INTO catalog_asset(" +
                "assetId,volumeName,mediaStoreId,mimeType,sizeBytes,width,height," +
                "orientationDegrees,generationAdded,generationModified,dateTakenMillis," +
                "dateModifiedSeconds,displayName,bucketId,bucketDisplayName,relativePath," +
                "sourceFingerprint,availability,lastSeenInventoryRunId," +
                "missingFullObservationCount,quarantineStartedAtMillis,sourceObservedAtMillis," +
                "derivedDataPurgedAtMillis" +
                ") VALUES (?, 'external_primary', ?, ?, 4194304, 4000, 3000, 0, 1, 1, ?, " +
                "1700000000, 'scale.jpg', ?, 'Camera', 'DCIM/Camera/', ?, 'AVAILABLE', " +
                "1, 0, NULL, 10000, NULL)"

        val OcrDocumentInsert =
            "INSERT INTO ocr_document(" +
                "assetId,sourceFingerprint,accessRevision,pipelineVersion,componentHash," +
                "publicationToken,publicationEpoch,sourceWidth,sourceHeight,rawText,displayText," +
                "canonicalText,stemText,identifierText,hasRecognizedText,regionCount," +
                "normalizerVersion,stemmerVersion,identifierVersion,publishedAtMillis" +
                ") VALUES (?, ?, $AccessRevision, '$PipelineVersion', '$ComponentHash', ?, ?, " +
                "4000, 3000, ?, ?, ?, ?, ?, 1, 1, 'norm-v1', 'stem-v1', 'id-v1', 20000)"

        val OcrRegionInsert =
            "INSERT INTO ocr_region(" +
                "publicationEpoch,assetId,ordinal,rawText,displayText,canonicalText," +
                "confidenceMicros,x0Micros,y0Micros,x1Micros,y1Micros,x2Micros,y2Micros," +
                "x3Micros,y3Micros" +
                ") VALUES (?, ?, 0, ?, ?, ?, 950000, 100000, 100000, 900000, 100000, " +
                "900000, 300000, 100000, 300000)"

        val LexicalInsert =
            "INSERT INTO ocr_lexical_fts(rowid,canonical,stems,identifiers) VALUES (?, ?, ?, ?)"

        val TrigramInsert =
            "INSERT INTO ocr_trigram_fts(rowid,canonical) VALUES (?, ?)"
    }
}
