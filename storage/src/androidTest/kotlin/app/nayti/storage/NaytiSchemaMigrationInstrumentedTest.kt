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
    fun migration1ToCurrentPreservesVectorRowsAndCreatesSemanticMetadata() = runBlocking {
        migration.createDatabase(StorageContract.InitialSchemaVersion).use { connection ->
            connection.execSQL(
                "INSERT INTO vector_segment_artifact " +
                    "(sha256, segmentId, relativePath, byteLength, formatVersion, channel, " +
                    "embeddingSpaceHash, dimension, recordCount, createdAtMillis) " +
                    "VALUES ('$SegmentSha', 'segment-v1', 'segments/v1.nvsg', 128, 1, " +
                    "'VISUAL', '$EmbeddingSha', 384, 1, 100)",
            )
            connection.execSQL(
                "INSERT INTO vector_segment_record " +
                    "(segmentSha256, ordinal, recordId, assetId, sourceFingerprint, chunkOrdinal) " +
                    "VALUES ('$SegmentSha', 0, 17, 7, 'source-v1', 3)",
            )
        }

        migration.runMigrationsAndValidate(
            StorageContract.CurrentSchemaVersion,
            listOf(
                StorageMigrations.From1To2,
                StorageMigrations.From2To3,
                StorageMigrations.From3To4,
            ),
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
            connection.prepare(
                "SELECT compactionLevel FROM vector_segment_artifact WHERE sha256 = ?",
            ).use { statement ->
                statement.bindText(1, SegmentSha)
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
                assertFalse(statement.step())
            }
            connection.execSQL(
                "INSERT INTO perceptual_hash_result " +
                    "(assetId, sourceFingerprint, accessRevision, pipelineVersion, componentHash, " +
                    "hashBits, publicationEpoch, createdAtMillis) " +
                    "VALUES (7, 'source-v1', 3, 'phash-v1', '$EmbeddingSha', 42, 11, 100)",
            )
            connection.prepare(
                "SELECT hashBits, publicationEpoch FROM perceptual_hash_result WHERE assetId = 7",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(42L, statement.getLong(0))
                assertEquals(11L, statement.getLong(1))
                assertFalse(statement.step())
            }
        }
    }

    private companion object {
        const val DatabaseName = "nayti-schema-migration.db"
        const val SegmentSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EmbeddingSha = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val ChunkSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SetSha = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
