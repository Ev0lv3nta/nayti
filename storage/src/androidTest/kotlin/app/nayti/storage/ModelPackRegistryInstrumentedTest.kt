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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPackRegistryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NaytiDatabase
    private lateinit var dao: ModelPackDao

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
    fun candidateRegistrationIsDurableAndIdempotent() = runBlocking {
        val candidate = candidate()

        assertEquals(candidate, dao.registerInstalledCandidate(candidate))
        assertEquals(candidate, dao.registerInstalledCandidate(candidate))
        reopenDatabase()

        assertEquals(listOf(candidate), dao.packs())
    }

    @Test
    fun sameVersionCannotPointToDifferentContent() = runBlocking {
        val candidate = candidate()
        dao.registerInstalledCandidate(candidate)

        val failure = runCatching {
            dao.registerInstalledCandidate(candidate.copy(manifestSha256 = "b".repeat(64)))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(candidate, dao.pack(candidate.packId, candidate.packVersion))
    }

    private fun candidate() =
        ModelPackEntity(
            packId = "nayti-offline-search",
            packVersion = "0.1.0-alpha.1",
            keyId = "1".repeat(32),
            manifestSha256 = "a".repeat(64),
            relativeDirectory = "nayti-offline-search/0.1.0-alpha.1-aaaaaaaaaaaaaaaa",
            payloadBytes = 1_013_962_735,
            installedAtMillis = 100,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, NaytiDatabase::class.java, DatabaseName)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        dao = database.modelPackDao()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private companion object {
        const val DatabaseName = "model-pack-registry-instrumented.db"
    }
}
