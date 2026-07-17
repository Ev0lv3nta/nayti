package app.nayti.storage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledFts5InstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: Fts5ProofDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @Test
    fun unicode61SupportsProductQueryShapes() = runBlocking {
        val dao = database.searchDao()
        dao.insertDocuments(fixtureDocuments())

        assertEquals(1L, database.scalarLong("SELECT sqlite_compileoption_used('ENABLE_FTS5')"))
        assertFalse(database.schemaSql("lexical_fts").contains("content="))
        assertFalse(database.schemaSql("trigram_fts").contains("content="))
        assertEquals(listOf(1L), dao.lexicalIds("\"счёт за кофе\"", 10))
        assertEquals(listOf(2L), dao.lexicalIds("cafe", 10))
        assertEquals(listOf(3L), dao.lexicalIds("NEAR(договор иванов, 2)", 10))
        assertEquals(listOf(3L), dao.lexicalIds("иван*", 10))
        assertEquals(listOf(1L), dao.lexicalIds("identifiers : \"ABC 123\"", 10))
        assertTrue(dao.firstSnippet("кофе").orEmpty().contains("<b>кофе</b>"))
    }

    @Test
    fun trigramReturnsBoundedTypoCandidates() = runBlocking {
        val dao = database.searchDao()
        dao.insertDocuments(fixtureDocuments())

        val candidates = dao.trigramIds(trigramCandidateQuery("рестаран"), 2)

        assertTrue(candidates.size <= 2)
        assertTrue(candidates.contains(4L))
    }

    @Test
    fun virtualIndexHandlesSyntheticLibraryScale() = runBlocking {
        val dao = database.searchDao()
        val corpus = (1L..SYNTHETIC_PHOTO_COUNT).map { id ->
            LexicalFtsDocument(
                rowId = id,
                canonical = "Фото $id чек кофе магазин ${id % 97}",
                stems = "фото чек кофе магазин",
                identifiers = "IMG-$id",
            )
        }
        val insertStartedAt = SystemClock.elapsedRealtimeNanos()
        dao.insertDocuments(corpus)
        val insertMillis = (SystemClock.elapsedRealtimeNanos() - insertStartedAt) / 1_000_000
        assertEquals(SYNTHETIC_PHOTO_COUNT, dao.lexicalCount())

        dao.lexicalIds("кофе \"магазин 42\"", 50)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val results = dao.lexicalIds("кофе \"магазин 42\"", 50)
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
        val elapsedMillis = elapsedNanos / 1_000_000
        val plan = database.queryPlan("кофе \"магазин 42\"")
        Log.i(
            LOG_TAG,
            "rows=$SYNTHETIC_PHOTO_COUNT insert_ms=$insertMillis " +
                "query_us=${elapsedNanos / 1_000} plan=$plan",
        )

        assertTrue(results.isNotEmpty())
        assertTrue("Query took ${elapsedMillis}ms", elapsedMillis < MAX_QUERY_MILLIS)
        assertTrue(plan.any { it.contains("VIRTUAL TABLE INDEX", ignoreCase = true) })
    }

    @Test
    fun walAllowsReadersAndOneWriterAndSurvivesReopen() = runBlocking {
        val dao = database.searchDao()
        dao.insertDocuments(fixtureDocuments())
        assertEquals("wal", database.journalMode().lowercase(Locale.ROOT))

        withTimeout(10_000) {
            coroutineScope {
                val readers = (1..4).map {
                    async(Dispatchers.Default) {
                        repeat(25) { dao.lexicalIds("кофе OR договор", 10) }
                    }
                }
                val writer = async(Dispatchers.Default) {
                    dao.insertDocuments(
                        (100L..149L).map { id ->
                            LexicalFtsDocument(id, "Новый документ $id", "новый документ", "N-$id")
                        },
                    )
                }
                (readers + writer).awaitAll()
            }
        }

        assertEquals(55L, dao.lexicalCount())
        database.close()
        database = openDatabase()
        assertEquals(55L, database.searchDao().lexicalCount())
    }

    @Test
    fun explicitMigrationPreservesRows() = runBlocking {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        val versionOne =
            Room.databaseBuilder(
                context,
                MigrationProofV1Database::class.java,
                MIGRATION_DATABASE_NAME,
            ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        try {
            versionOne.useConnection(isReadOnly = false) { connection ->
                connection.usePrepared(
                    "INSERT INTO migration_probe(id, value) VALUES (?, ?)",
                ) { statement ->
                    statement.bindLong(1, 7)
                    statement.bindText(2, "preserved")
                    statement.step()
                }
            }
        } finally {
            versionOne.close()
        }

        val versionTwo =
            Room.databaseBuilder(
                context,
                MigrationProofV2Database::class.java,
                MIGRATION_DATABASE_NAME,
            ).setDriver(BundledSQLiteDriver())
                .addMigrations(migrationProof1To2)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        try {
            val migrated = versionTwo.useConnection(isReadOnly = true) { connection ->
                connection.usePrepared(
                    "SELECT value, revision FROM migration_probe WHERE id = 7",
                ) { statement ->
                    check(statement.step())
                    statement.getText(0) to statement.getLong(1)
                }
            }
            assertEquals("preserved" to 0L, migrated)
        } finally {
            versionTwo.close()
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    private fun openDatabase(): Fts5ProofDatabase =
        Room.databaseBuilder(context, Fts5ProofDatabase::class.java, DATABASE_NAME)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setMultipleConnectionPool(maxNumOfReaders = 4, maxNumOfWriters = 1)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    private suspend fun Fts5ProofDatabase.journalMode(): String =
        useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("PRAGMA journal_mode") { statement ->
                check(statement.step())
                statement.getText(0)
            }
        }

    private suspend fun Fts5ProofDatabase.queryPlan(query: String): List<String> =
        useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(
                "EXPLAIN QUERY PLAN SELECT rowid FROM lexical_fts " +
                    "WHERE lexical_fts MATCH ? LIMIT 50",
            ) { statement ->
                statement.bindText(1, query)
                buildList {
                    while (statement.step()) add(statement.getText(3))
                }
            }
        }

    private suspend fun Fts5ProofDatabase.scalarLong(sql: String): Long =
        useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(sql) { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

    private suspend fun Fts5ProofDatabase.schemaSql(table: String): String =
        useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            ) { statement ->
                statement.bindText(1, table)
                check(statement.step())
                statement.getText(0)
            }
        }

    private fun fixtureDocuments(): List<LexicalFtsDocument> =
        listOf(
            LexicalFtsDocument(1, "Счёт за кофе в июне", "счёт кофе июнь", "ABC-123"),
            LexicalFtsDocument(2, "Café receipt from June", "cafe receipt june", "CAFE-9"),
            LexicalFtsDocument(3, "Договор с Ивановым", "договор иванов", "DOC-77"),
            LexicalFtsDocument(4, "Ресторан на Невском", "ресторан невский", "PLACE-4"),
            LexicalFtsDocument(5, "Закат у моря", "закат море", "PHOTO-5"),
        )

    private fun trigramCandidateQuery(value: String): String =
        value.lowercase(Locale.ROOT)
            .windowed(size = 3, step = 1, partialWindows = false)
            .distinct()
            .joinToString(" OR ") { gram -> "\"${gram.replace("\"", "\"\"")}\"" }

    private companion object {
        const val DATABASE_NAME = "fts5-proof.db"
        const val MIGRATION_DATABASE_NAME = "migration-proof.db"
        const val LOG_TAG = "NaytiFtsProof"
        const val SYNTHETIC_PHOTO_COUNT = 13_000L
        const val MAX_QUERY_MILLIS = 2_000L
    }
}
