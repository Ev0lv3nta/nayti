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
import org.junit.After
import org.junit.Before
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

    @Before
    fun deletePreviousDatabase() {
        instrumentation.targetContext.deleteDatabase(DatabaseName)
    }

    @After
    fun deleteTestDatabase() {
        instrumentation.targetContext.deleteDatabase(DatabaseName)
    }

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
                StorageMigrations.From4To5,
                StorageMigrations.From5To6,
                StorageMigrations.From6To7,
                StorageMigrations.From7To8,
            ),
        ).use { connection ->
            connection.prepare(
                "SELECT recordId, assetId, semanticChunkId, accessRevision " +
                    "FROM vector_segment_record WHERE segmentSha256 = ?",
            ).use { statement ->
                statement.bindText(1, SegmentSha)
                assertTrue(statement.step())
                assertEquals(17L, statement.getLong(0))
                assertEquals(7L, statement.getLong(1))
                assertTrue(statement.isNull(2))
                assertEquals(1L, statement.getLong(3))
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
            connection.prepare("PRAGMA table_info(`activation_snapshot`)").use { statement ->
                val columns = mutableSetOf<String>()
                while (statement.step()) columns += statement.getText(1)
                assertTrue("formatVersion" in columns)
                assertTrue("capturedAccessRevision" in columns)
            }
            connection.prepare("SELECT COUNT(*) FROM activation_candidate").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            connection.prepare("SELECT COUNT(*) FROM activation_snapshot_channel").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun migration5To6BackfillsPerChannelSnapshotContracts() = runBlocking {
        migration.createDatabase(5).use { connection ->
            connection.execSQL(
                "INSERT INTO vector_generation " +
                    "(generationId, channel, packId, packVersion, pipelineVersion, componentHash, " +
                    "embeddingSpaceHash, dimension, state, createdAtMillis, sealedAtMillis) VALUES " +
                    "('semantic-v1', 'OCR_SEMANTIC', 'nayti-offline-search', '0.1.0-alpha.1', " +
                    "'semantic-v1', '$SegmentSha', '$EmbeddingSha', 384, 'SEALED', 1, 2), " +
                    "('visual-v1', 'VISUAL', 'nayti-offline-search', '0.1.0-alpha.1', " +
                    "'visual-v1', '$ChunkSha', '$SetSha', 768, 'SEALED', 1, 2)",
            )
            connection.execSQL(
                "INSERT INTO vector_manifest " +
                    "(revision, generationId, parentRevision, channel, relativePath, byteLength, sha256, " +
                    "segmentCount, recordCount, createdAtMillis) VALUES " +
                    "('semantic-manifest', 'semantic-v1', NULL, 'OCR_SEMANTIC', 'manifests/semantic', 1, " +
                    "'$SegmentSha', 1, 1, 2), " +
                    "('visual-manifest', 'visual-v1', NULL, 'VISUAL', 'manifests/visual', 1, " +
                    "'$ChunkSha', 1, 1, 2)",
            )
            connection.execSQL(
                "INSERT INTO activation_snapshot " +
                    "(snapshotId, parentSnapshotId, packId, packVersion, packManifestSha256, " +
                    "engineContractVersion, rankingConfigVersion, lexicalPublicationEpoch, " +
                    "pHashPublicationEpoch, semanticManifestRevision, visualManifestRevision, " +
                    "catalogWatermark, createdAtMillis, formatVersion, capturedAccessRevision) VALUES " +
                    "('snapshot-v5', NULL, 'nayti-offline-search', '0.1.0-alpha.1', '$PackSha', 1, " +
                    "'ranking-v1', 7, 8, 'semantic-manifest', 'visual-manifest', 9, 10, 1, 11)",
            )
        }

        migration.runMigrationsAndValidate(6, listOf(StorageMigrations.From5To6)).use { connection ->
            connection.prepare(
                "SELECT channel, pipelineVersion, componentHash, generationId, manifestRevision " +
                    "FROM activation_snapshot_channel WHERE snapshotId = 'snapshot-v5' ORDER BY channel",
            ).use { statement ->
                val rows = mutableListOf<List<String?>>()
                while (statement.step()) {
                    rows += (0..4).map { index -> if (statement.isNull(index)) null else statement.getText(index) }
                }
                assertEquals(listOf("OCR", "OCR_SEMANTIC", "PHASH", "VISUAL"), rows.map { it[0] })
                assertEquals(SegmentSha, rows.single { it[0] == "OCR_SEMANTIC" }[2])
                assertEquals("semantic-manifest", rows.single { it[0] == "OCR_SEMANTIC" }[4])
                assertEquals(ChunkSha, rows.single { it[0] == "VISUAL" }[2])
                assertEquals(PackSha, rows.single { it[0] == "OCR" }[2])
            }
        }
    }

    @Test
    fun migration6To7RejectsUnplannedIncompleteCandidate() = runBlocking {
        migration.createDatabase(6).use { connection ->
            connection.execSQL(
                "INSERT INTO activation_candidate " +
                    "(candidateId, snapshotId, parentSnapshotId, packId, packVersion, packManifestSha256, " +
                    "capturedAccessRevision, capturedCatalogWatermark, state, createdAtMillis, " +
                    "updatedAtMillis, failureCode) VALUES " +
                    "('candidate-v6', 'snapshot-v6', NULL, 'nayti-offline-search', '0.1.0-alpha.2', " +
                    "'$PackSha', 1, 2, 'READY_TO_ACTIVATE', 3, 4, NULL)",
            )
        }

        migration.runMigrationsAndValidate(7, listOf(StorageMigrations.From6To7)).use { connection ->
            connection.prepare(
                "SELECT state, failureCode FROM activation_candidate WHERE candidateId = 'candidate-v6'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("REJECTED", statement.getText(0))
                assertEquals("MISSING_CHANNEL_PLAN", statement.getText(1))
                assertFalse(statement.step())
            }
            connection.prepare("SELECT COUNT(*) FROM activation_candidate_channel").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun migration7To8VersionsOcrDocumentsAndRebuildsFtsRows() = runBlocking {
        migration.createDatabase(7).use { connection ->
            connection.execSQL(
                "INSERT INTO ocr_document " +
                    "(assetId, sourceFingerprint, accessRevision, pipelineVersion, componentHash, " +
                    "publicationToken, publicationEpoch, sourceWidth, sourceHeight, rawText, displayText, " +
                    "canonicalText, stemText, identifierText, hasRecognizedText, regionCount, " +
                    "normalizerVersion, stemmerVersion, identifierVersion, publishedAtMillis) VALUES " +
                    "(7, '$SegmentSha', 1, 'ocr-v1', '$EmbeddingSha', 'ocr-token-v7', 41, 100, 200, " +
                    "'Quarterly report', 'Quarterly report', 'quarterly report', 'quarter report', '', " +
                    "1, 1, 'normalizer-v1', 'stemmer-v1', 'identifier-v1', 42)",
            )
            connection.execSQL(
                "INSERT INTO ocr_region " +
                    "(assetId, ordinal, rawText, displayText, canonicalText, confidenceMicros, " +
                    "x0Micros, y0Micros, x1Micros, y1Micros, x2Micros, y2Micros, x3Micros, y3Micros) " +
                    "VALUES (7, 0, 'Quarterly report', 'Quarterly report', 'quarterly report', 900000, " +
                    "0, 0, 1, 0, 1, 1, 0, 1)",
            )
            connection.execSQL(
                "INSERT INTO ocr_lexical_fts(rowid, canonical, stems, identifiers) " +
                    "VALUES (7, 'quarterly report', 'quarter report', '')",
            )
            connection.execSQL(
                "INSERT INTO ocr_trigram_fts(rowid, canonical) VALUES (7, 'quarterly report')",
            )
        }

        migration.runMigrationsAndValidate(8, listOf(StorageMigrations.From7To8)).use { connection ->
            connection.prepare(
                "SELECT assetId, publicationToken FROM ocr_document WHERE publicationEpoch = 41",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(7L, statement.getLong(0))
                assertEquals("ocr-token-v7", statement.getText(1))
                assertFalse(statement.step())
            }
            connection.prepare(
                "SELECT assetId, ordinal FROM ocr_region WHERE publicationEpoch = 41",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(7L, statement.getLong(0))
                assertEquals(0L, statement.getLong(1))
                assertFalse(statement.step())
            }
            connection.prepare(
                "SELECT document.assetId FROM ocr_lexical_fts " +
                    "INNER JOIN ocr_document AS document " +
                    "ON document.publicationEpoch = ocr_lexical_fts.rowid " +
                    "WHERE ocr_lexical_fts MATCH 'quarterly'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(7L, statement.getLong(0))
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
        const val PackSha = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
