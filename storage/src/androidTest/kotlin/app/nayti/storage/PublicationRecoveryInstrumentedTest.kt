package app.nayti.storage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicationRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root: File = context.filesDir.resolve("publication-proof")
    private lateinit var database: PublicationProofDatabase
    private lateinit var harness: PublicationProofHarness

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        check(root.deleteRecursively())
        check(root.mkdirs())
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        check(root.deleteRecursively())
    }

    @Test
    fun oneHundredCrashPointsNeverExposePartialPublication() = runBlocking {
        val seed = harness.publish("seed", payload(0), parentSnapshotId = null)
        assertEquals(seed.snapshotId, harness.activeSnapshotId())
        val startedAt = SystemClock.elapsedRealtimeNanos()

        repeat(CRASH_ITERATIONS) { iteration ->
            val failpoint = PUBLICATION_FAILPOINTS[iteration % PUBLICATION_FAILPOINTS.size]
            val previousActive = harness.activeSnapshotId()
            val token = "crash-$iteration"
            expectCrash(failpoint) {
                harness.publish(token, payload(iteration + 1), previousActive, failpoint)
            }

            reopenDatabase()
            val report = harness.recover(nowMillis = System.currentTimeMillis() + 1_000)
            val expectedActive =
                if (failpoint == PublicationFailpoint.AFTER_DB_COMMIT) {
                    "$token-snapshot"
                } else {
                    previousActive
                }
            assertEquals(expectedActive, report.activeAfter)
            assertEquals(expectedActive, harness.activeSnapshotId())
            assertEquals(
                if (failpoint == PublicationFailpoint.AFTER_DB_COMMIT) {
                    PublicationProofDao.PUBLICATION_DONE
                } else {
                    PublicationProofDao.PUBLICATION_ABANDONED
                },
                harness.publicationState(token),
            )
            assertEquals(0, harness.temporaryFileCount())
            assertEquals(harness.committedArtifactPaths(), harness.sealedArtifactPaths())
            assertTrue(harness.activeArtifactPaths().all { root.resolve(it).isFile })

            val secondRecovery = harness.recover(nowMillis = System.currentTimeMillis() + 1_000)
            assertEquals(expectedActive, secondRecovery.activeAfter)
            assertEquals(0, secondRecovery.abandonedPublications)
            assertEquals(0, secondRecovery.deletedTemps)
            assertEquals(0, secondRecovery.deletedOrphans)
            assertEquals(0, secondRecovery.replayedDeleteIntents)
        }

        Log.i(
            LOG_TAG,
            "crash_points=$CRASH_ITERATIONS elapsed_ms=" +
                "${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000}",
        )
        Unit
    }

    @Test
    fun corruptActiveRollsBackToVerifiedParentThenDegradesExplicitly() = runBlocking {
        val parent = harness.publish("parent", payload(1), parentSnapshotId = null)
        val child = harness.publish("child", payload(2), parentSnapshotId = parent.snapshotId)
        harness.corruptSegment(child.snapshotId)

        reopenDatabase()
        val rollback = harness.recover(System.currentTimeMillis())
        assertEquals(child.snapshotId, rollback.activeBefore)
        assertEquals(parent.snapshotId, rollback.activeAfter)

        harness.corruptSegment(parent.snapshotId)
        reopenDatabase()
        val degraded = harness.recover(System.currentTimeMillis())
        assertEquals(parent.snapshotId, degraded.activeBefore)
        assertNull(degraded.activeAfter)
        assertTrue(harness.activeArtifactPaths().isEmpty())
        assertNull(harness.recover(System.currentTimeMillis()).activeAfter)
    }

    @Test
    fun liveLeasePinsSnapshotAndDeleteIntentReplaysAfterCrash() = runBlocking {
        val now = System.currentTimeMillis()
        val old = harness.publish("leased-old", payload(3), parentSnapshotId = null)
        harness.acquireQueryLease("query-lease", old.snapshotId, now + 60_000)
        val current = harness.publish("current", payload(4), parentSnapshotId = null)
        assertFalse(harness.collectSnapshot(old.snapshotId, now))
        assertEquals(current.snapshotId, harness.activeSnapshotId())

        reopenDatabase()
        val staleLeaseRecovery = harness.recover(now + 120_000)
        assertEquals(1, staleLeaseRecovery.expiredLeases)
        expectCrash(PublicationFailpoint.AFTER_DELETE_INTENT) {
            harness.collectSnapshot(
                old.snapshotId,
                now + 120_000,
                PublicationFailpoint.AFTER_DELETE_INTENT,
            )
        }

        reopenDatabase()
        val intentRecovery = harness.recover(now + 120_000)
        assertEquals(2, intentRecovery.replayedDeleteIntents)
        assertFalse(harness.snapshotExists(old.snapshotId))
        assertEquals(current.snapshotId, harness.activeSnapshotId())

        val unlinked = harness.publish("unlinked", payload(5), parentSnapshotId = null)
        val final = harness.publish("final", payload(6), parentSnapshotId = null)
        expectCrash(PublicationFailpoint.AFTER_DELETE_UNLINK) {
            harness.collectSnapshot(
                unlinked.snapshotId,
                now + 120_000,
                PublicationFailpoint.AFTER_DELETE_UNLINK,
            )
        }
        reopenDatabase()
        val unlinkRecovery = harness.recover(now + 120_000)
        assertEquals(2, unlinkRecovery.replayedDeleteIntents)
        assertFalse(harness.snapshotExists(unlinked.snapshotId))
        assertEquals(final.snapshotId, harness.activeSnapshotId())

        val confirmed = harness.publish("confirmed", payload(9), parentSnapshotId = null)
        val finalAfterConfirmation =
            harness.publish("final-after-confirmation", payload(10), parentSnapshotId = null)
        expectCrash(PublicationFailpoint.BEFORE_DELETE_DB_FINALIZE) {
            harness.collectSnapshot(
                confirmed.snapshotId,
                now + 120_000,
                PublicationFailpoint.BEFORE_DELETE_DB_FINALIZE,
            )
        }
        reopenDatabase()
        val confirmationRecovery = harness.recover(now + 120_000)
        assertEquals(0, confirmationRecovery.replayedDeleteIntents)
        assertFalse(harness.snapshotExists(confirmed.snapshotId))
        assertEquals(finalAfterConfirmation.snapshotId, harness.activeSnapshotId())
        assertEquals(harness.committedArtifactPaths(), harness.sealedArtifactPaths())
    }

    @Test
    fun orphanGracePreventsPrematureDeletion() = runBlocking {
        val parent = harness.publish("grace-parent", payload(7), parentSnapshotId = null)
        expectCrash(PublicationFailpoint.AFTER_SEGMENT_RENAME) {
            harness.publish(
                "grace-orphan",
                payload(8),
                parent.snapshotId,
                PublicationFailpoint.AFTER_SEGMENT_RENAME,
            )
        }
        val sealedBefore = harness.sealedArtifactPaths()
        assertTrue(sealedBefore.size > harness.committedArtifactPaths().size)

        reopenDatabase()
        val now = System.currentTimeMillis()
        val guarded = harness.recover(now, orphanGraceMillis = ORPHAN_GRACE_MILLIS)
        assertEquals(0, guarded.deletedOrphans)
        assertTrue(harness.sealedArtifactPaths().size > harness.committedArtifactPaths().size)

        val expired =
            harness.recover(
                now + ORPHAN_GRACE_MILLIS * 2,
                orphanGraceMillis = ORPHAN_GRACE_MILLIS,
            )
        assertEquals(1, expired.deletedOrphans)
        assertEquals(harness.committedArtifactPaths(), harness.sealedArtifactPaths())
        assertNotNull(harness.activeSnapshotId())
    }

    @Test
    fun sharedContentAddressedSegmentSurvivesSnapshotCollection() = runBlocking {
        val sharedPayload = payload(11)
        val old = harness.publish("shared-old", sharedPayload, parentSnapshotId = null)
        val current = harness.publish("shared-current", sharedPayload, parentSnapshotId = null)
        val sharedSegment = harness.activeArtifactPaths().single { it.endsWith(".naytivec") }

        assertTrue(harness.collectSnapshot(old.snapshotId, System.currentTimeMillis()))
        assertFalse(harness.snapshotExists(old.snapshotId))
        assertEquals(current.snapshotId, harness.activeSnapshotId())
        assertTrue(root.resolve(sharedSegment).isFile)
        assertEquals(harness.committedArtifactPaths(), harness.sealedArtifactPaths())

        reopenDatabase()
        val recovery = harness.recover(System.currentTimeMillis())
        assertEquals(0, recovery.replayedDeleteIntents)
        assertEquals(current.snapshotId, recovery.activeAfter)
        assertTrue(root.resolve(sharedSegment).isFile)
    }

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, PublicationProofDatabase::class.java, DATABASE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setMultipleConnectionPool(maxNumOfReaders = 2, maxNumOfWriters = 1)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        harness = PublicationProofHarness(root, database)
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private suspend fun expectCrash(
        expected: PublicationFailpoint,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected simulated process death at $expected")
        } catch (crash: SimulatedProcessDeath) {
            assertEquals(expected, crash.failpoint)
        }
    }

    private fun payload(seed: Int): ByteArray =
        ByteArray(PAYLOAD_BYTES) { index -> ((seed * 31 + index * 17) and 0xff).toByte() }

    private companion object {
        const val DATABASE_NAME = "publication-proof.db"
        const val LOG_TAG = "NaytiPublicationProof"
        const val CRASH_ITERATIONS = 100
        const val PAYLOAD_BYTES = 1_024
        const val ORPHAN_GRACE_MILLIS = 60_000L

        val PUBLICATION_FAILPOINTS =
            listOf(
                PublicationFailpoint.AFTER_TEMP_WRITE,
                PublicationFailpoint.AFTER_SEGMENT_FSYNC,
                PublicationFailpoint.AFTER_SEGMENT_RENAME,
                PublicationFailpoint.AFTER_SEGMENT_DIRECTORY_SYNC,
                PublicationFailpoint.AFTER_MANIFEST_FSYNC,
                PublicationFailpoint.AFTER_MANIFEST_RENAME,
                PublicationFailpoint.AFTER_MANIFEST_DIRECTORY_SYNC,
                PublicationFailpoint.BEFORE_DB_COMMIT,
                PublicationFailpoint.INSIDE_DB_TRANSACTION,
                PublicationFailpoint.AFTER_DB_COMMIT,
            )
    }
}
