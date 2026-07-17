package app.nayti.storage

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaytiSchemaMigrationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migration =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = instrumentation.targetContext.getDatabasePath(DatabaseName),
            driver = BundledSQLiteDriver(),
            databaseClass = NaytiDatabase::class,
        )

    @Test
    fun migration1To2PreservesVectorRowsAndCreatesSemanticMetadata() = runBlocking {
        migration.createDatabase(StorageContract.InitialSchemaVersion).use { connection ->
            connection.execSQL(
                "INSERT INTO vector_segment_record " +
                    "(segmentSha256, ordinal, recordId, assetId, sourceFingerprint, chunkOrdinal) " +
                    "VALUES ('$SegmentSha', 0, 17, 7, 'source-v1', 3)",
            )
        }

        migration.runMigrationsAndValidate(
            StorageContract.CurrentSchemaVersion,
            listOf(StorageMigrations.From1To2),
        ).use { connection ->
            connection.prepare(
                "SELECT recordId, assetId, semanticChunkId FROM vector_segment_record WHERE segmentSha256 = ?",
            ).use { statement ->
                statement.bindText(1, SegmentSha)
                assertTrue(statement.step())
                assertEquals(17L, statement.getLong(0))
                assertEquals(7L, statement.getLong(1))
                assertTrue(statement.isNull(2))
                assertFalse(statement.step())
            }

            connection.execSQL(
                "INSERT INTO ocr_semantic_chunk_set " +
                    "(chunkSetId, assetId, sourceFingerprint, ocrPublicationToken, chunkingVersion, " +
                    "chunkCount, payloadSha256, payloadByteLength, createdAtMillis) " +
                    "VALUES ('$SetSha', 7, 'source-v1', 'ocr-token', 'ocr-semantic-chunks-v1', " +
                    "1, '$SetSha', 100, 100)",
            )
            connection.execSQL(
                "INSERT INTO ocr_semantic_chunk " +
                    "(chunkId, chunkSetId, assetId, sourceFingerprint, ocrPublicationToken, ordinal, kind, " +
                    "displayText, contentTokenCount, firstLineOrdinal, lastLineOrdinal, " +
                    "meanConfidenceMicros, reliableAlphabeticWordCount, chunkingVersion, createdAtMillis) " +
                    "VALUES ('$ChunkSha', '$SetSha', 7, 'source-v1', 'ocr-token', 0, 'HEADER', " +
                    "'quarterly report text', 3, 0, 1, 900000, 3, 'ocr-semantic-chunks-v1', 100)",
            )
            connection.execSQL(
                "INSERT INTO ocr_semantic_chunk_line (chunkId, position, assetId, lineOrdinal) " +
                    "VALUES ('$ChunkSha', 0, 7, 0)",
            )
        }
    }

    private companion object {
        const val DatabaseName = "nayti-schema-migration.db"
        const val SegmentSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ChunkSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SetSha = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
